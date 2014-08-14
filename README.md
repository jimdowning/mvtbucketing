mvtbucketing
============

Simple MVT bucketing code

## Usage

    (mvt-bucketing/assign-bucket
      [ {:episode "1" :release true :control 0.5 
         :active {:blue-button 1.0}}
        {:episode "2" :control 0.3 :active {:blue-button 0.4
                                           :red-button 0.25
                                           :orange-button 0.35}}
        {:episode "3" :control 0.2 :active {:blue-button 0.5
                                           :red-button 0.5}}] ;; The test config
      ["1" :orange-button] ;; User's existing bucket
      0.2) ;; User's seed (should be randomly distributed between users)

Users eligible for bucketing will be assigned either the control bucket or one of the active buckets according to the weighting values in the config for the last episode. Users are eligible for rebucketing iff they have no bucket, or the bucket they have been assigned to isn't part of an unreleased episode. 
