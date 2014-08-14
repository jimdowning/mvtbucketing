(ns mvt-bucketing.core-test
  (:require [clojure.set :as set]
            [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :as ct]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mvt-bucketing.core :as bucket]))

(def example-config
  [{:episode "0" :control 50 :active {:blue-button 100} :release true}
   {:episode "1" :control 30 :active {:blue-button 40
                                      :red-button 25
                                      :orange-button 35}}
   {:episode "2" :control 20 :active {:blue-button 50
                                      :red-button 50}}])

(def variants-gen (gen/not-empty
                   (gen/resize 5
                               (gen/map gen/keyword
                                        (gen/resize 250 gen/nat)))))

(defn make-episode-gen
  "An episode generator factory. If provided, control-gen is a
  generator for the control value."
  ([]
   (make-episode-gen (gen/such-that #(<= % 100) gen/nat)))
  ([control]
   (gen/fmap (partial apply hash-map)
             (gen/tuple (gen/return :episode)
                        (gen/such-that #(> (count %) 1) gen/string)
                        (gen/return :control)
                        control
                        (gen/return :active)
                        variants-gen))))

(def config-gen (gen/not-empty
                 (gen/resize 50
                             (gen/vector (make-episode-gen)))))

(defn bucket-gen [episode]
  (gen/tuple
   (gen/return (:episode episode))
   (gen/elements (conj (keys (:active episode))
                       :control))))

(def config-and-bucket-gen
  (gen/bind config-gen
            (fn [config]
              (gen/tuple (gen/return config)
                         (gen/one-of [(gen/return nil)
                                      (gen/bind (gen/elements config)
                                                bucket-gen)])))))

(defn bucket-is-from-episode?
  [[episode-label bucket] episode]
  (and (= (:episode episode) episode-label)
       (or (some #{bucket} (keys (:active episode)))
           (= bucket :control))))

(def seed-gen (gen/resize 1000000 gen/int))

(def bucket-consistent-with-config
  (prop/for-all [[c b] config-and-bucket-gen seed seed-gen]
                (let [[episode bucket] (bucket/assign-bucket c b seed)]
                  (some (fn [e] (bucket-is-from-episode? [episode bucket] e))
                        c))))

(ct/defspec generators-and-api-basically-work
            100
            bucket-consistent-with-config)

(def result-is-from-current-episode
  (prop/for-all [config config-gen seed seed-gen]
                (let [[episode bucket] (bucket/assign-bucket config nil seed)
                      current-episode (:episode (last config))]
                  (= episode current-episode))))

(ct/defspec new-visitors
            50
            result-is-from-current-episode)

(ct/defspec deterministic
            50
            (prop/for-all [config config-gen seed gen/int]
                          (let [b1 (bucket/assign-bucket config nil seed)
                                b2 (bucket/assign-bucket config nil seed)]
                            (= b1 b2))))

;; Refactor Jim: Can we test fewer seeds and test for > some level of
;; distribution such that there is a known (low) chance of a false negative.
;; Would need to fix the configs up a bit better I think.
(deftest all-active-reachable-for-new-visitors
  (is (every? identity
              (for [c (gen/sample config-gen 10)]
                (let [potential-buckets (into #{}
                                              (keys (:active (last c))))
                      seeds (gen/sample seed-gen 1000)
                      buckets (map #(second
                                     (bucket/assign-bucket c nil %))
                                   seeds)
                      bucket-set (into #{} buckets)]
                  (every? bucket-set
                          potential-buckets))))))

(ct/defspec control-never-picked-when-0
            100
            (prop/for-all
             [config (gen/tuple (make-episode-gen (gen/return 0)))
              seed seed-gen]
             (not= :control (second (bucket/assign-bucket config nil seed)))))

(ct/defspec control-always-picked-when-100
            100
            (prop/for-all
             [config (gen/tuple (make-episode-gen (gen/return 100)))
              seed seed-gen]
             (= :control (second (bucket/assign-bucket config nil seed)))))


(def test-config [{:episode "1" :control 30 :active {:blue-button 40
                                                     :red-button 25
                                                     :orange-button 35
                                                     :purple-button 20}
                   :release true}
                  {:episode "2" :control 50 :active {:blue-button 40
                                                     :red-button 25
                                                     :orange-button 35
                                                     :yellow-button 25}}
                  {:episode "3" :control 20 :active {:blue-button 50
                                                     :purple-button 50}}])

(deftest sticky-buckets
    (is (=
         (apply hash-set
                (bucket/sticky-buckets
                 test-config))
         (hash-set ["3" :control] ["3" :blue-button] ["3" :purple-button]
                   ["2" :control] ["2" :blue-button]))))

(def control-retained-for-active-variants-prop
  (prop/for-all [seed seed-gen
                 v (gen/such-that #(some #{%} (keys (:active (last test-config))))
                                  (gen/elements (keys (:active (second test-config)))))]
                (= ["2" v] (bucket/assign-bucket test-config ["2" v] seed))))

(ct/defspec control-retained-for-active-variants
            100
            control-retained-for-active-variants-prop)

(def all-released-for-released-episode-prop
  (prop/for-all [seed seed-gen
                 v (gen/elements (conj (keys (:active (first test-config))) :control))]
                (not= ["1" v] (bucket/assign-bucket test-config ["1" v] seed))))

(ct/defspec all-released-for-released-episode
            100
            all-released-for-released-episode-prop)
