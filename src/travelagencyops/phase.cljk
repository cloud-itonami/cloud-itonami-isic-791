(ns travelagencyops.phase
  "Rollout phases: 0→3 adoption path for TravelAgencyOps actor.")

(def default-phase :phase-3)

(defn verdict->disposition
  "Convert a governor verdict into an initial disposition."
  [verdict]
  (cond
    (:hard? verdict)     :hold
    (:escalate? verdict) :escalate
    :else                :commit))

(defn gate
  "The phase gate: applies rollout-phase policy to a verdict.
  Phase 0: ALL operations escalate (sandbox)
  Phase 1: High-stakes escalate
  Phase 2: Only safety concerns escalate
  Phase 3: Fully autonomous"
  [phase request base-disposition]
  (case phase
    :phase-0
    {:disposition :escalate :reason :phase-0-pilot}

    :phase-1
    (case base-disposition
      :hold     {:disposition :hold}
      :escalate {:disposition :escalate :reason :phase-1-high-stakes}
      :commit   (if (= (:op request) :flag-safety-concern)
                  {:disposition :escalate :reason :phase-1-escalate-high-stakes}
                  {:disposition :commit}))

    :phase-2
    (case base-disposition
      :hold     {:disposition :hold}
      :escalate (if (= (:op request) :flag-safety-concern)
                  {:disposition :escalate :reason :phase-2-safety-escalate}
                  {:disposition :commit})
      :commit   {:disposition :commit})

    :phase-3
    (case base-disposition
      :hold     {:disposition :hold}
      :escalate {:disposition :escalate :reason :phase-3-escalate}
      :commit   {:disposition :commit})

    {:disposition base-disposition}))
