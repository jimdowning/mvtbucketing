(ns mvt-bucketing.core
  (:require [clojure.set :as s :only intersection]))

(defn- natural? [a]
  (when (and (integer? a)
             (or (zero? a)
                 (pos? a)))
    a))

(defn- natural-percent? [a]
  (when (and (natural? a)
             (<= a 100))
    a))

(defn- valid-variant?
  [[label proportion]]
  (and (keyword? label)
       (natural? proportion)))

(defn- valid-episode?
  [e]
  (and (contains? e :control)
       (natural-percent? (:control e))
       (contains? e :episode)
       (string? (:episode e))
       (pos? (count (:episode e)))
       (not-empty (:active e))
       (every? valid-variant? (:active e))))

(defn valid-test-config?
  [test-config]
  (and
    (every? valid-episode? test-config)))

(defn valid-bucket?
  [b]
  (and (vector? b)
       (= 2 (count b))
       (string? (first b))
       (keyword? (second b))))

(defn sticky-buckets
  "Takes a test config and works out which buckets should retain users placed
  in them, returning them in a set"
  [test-config]
  {:pre [(valid-test-config? test-config)]}
  (let [retained-episodes (filter (complement :release) test-config)
        active-variants (into #{} (keys (:active (last test-config))))]
    (->>
     (for [e retained-episodes]
       (conj (map (fn [v] [(:episode e) v])
                  (s/intersection active-variants
                                  (into #{} (keys (:active e)))))
             [(:episode e) :control]))
     (reduce concat))))

(defn- select-variant
  "For a map of [variant-key weighting] entries, and a user seed between 0.0
  and 1.0, return the variant-key for the indicated variant."
  [variants user-val]
  {:pre []
   :post [ % ]}
  (ffirst (drop-while #(> user-val (second %)) variants)))

(defn- disperse
  "A pseudo random number generator that first shuffles the user seed then
  generates a random int in the desired range. "
  [user-seed limit]
  (loop [i user-seed n 10]
    (let [r (java.util.Random. i)]
      (if (< n 1)
        (.nextInt r limit)
        (recur (.nextInt r) (dec n))))))

(defn- control?
  [control-percent user-seed]
  (> control-percent (disperse user-seed 100)))

(defn- accumulate
  "Takes a seq of [keyword int] tuples and returns the same list with ints accumulated"
  [variants]
  (reductions (fn [[_ s] [variant a]]
                  [variant (+ s a)])
              variants))

(defn assign-bucket
  "Given an MVT test config, the user's existing bucket assignment (or nil
  if they don't have one) and the user's seed (any int - seeds are dispersed
  between buckets)."
  [test-config existing-bucket user-seed]
  {:pre [(integer? user-seed)
         (valid-test-config? test-config)
         (or (nil? existing-bucket)
             (valid-bucket? existing-bucket))]}
  (let [{:keys [episode control active]} (last test-config)
        sticky (sticky-buckets test-config)]
    (if (some #{existing-bucket} sticky)
      existing-bucket
      (if (control? control user-seed)
        [episode :control]
        (let [variants (sort active)
              accumulated (accumulate variants)
              total (second (last accumulated))]
          [episode (select-variant accumulated
                                   (disperse user-seed total))])))))
