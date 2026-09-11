(ns travelagencyops.sim
  "TravelAgencyOps simulation harness -- demo actor with mock advisor and in-memory store."
  (:require [travelagencyops.store :as store]))

(defn -main [& args]
  (println "TravelAgencyOps ISIC-791 Actor -- Travel Agency Booking Coordination")
  (println "=" (apply str (repeat 67 "=")))
  (println)

  (println "Actor Configuration:")
  (println "  Governor checks:")
  (println "    1. Booking-record must be independently verified")
  (println "    2. Effect must be :propose")
  (println "    3. No visa/insurance/refund/authority overrides")
  (println)
  (println "  Closed operations:")
  (println "    • :schedule-itinerary-booking")
  (println "    • :coordinate-booking-status-update")
  (println "    • :coordinate-supply-request")
  (println "    • :schedule-staff-shift-proposal")
  (println "    • :flag-safety-concern")
  (println)

  (println "Rollout phases:")
  (println "  Phase 0: All operations escalate (sandbox)")
  (println "  Phase 1: High-stakes escalate")
  (println "  Phase 2: Only safety concerns escalate")
  (println "  Phase 3: Fully autonomous")
  (println)

  ;; printed from the seed itself, so this never drifts from store.cljc
  (let [st (store/demo-store)
        clients (store/all-clients st)]
    (println "Store initialized with demo bookings:")
    (doseq [[id b] (sort-by key (store/all-bookings st))]
      (println (str "  " id ": "
                    (get-in clients [(:client-id b) :name])
                    ", " (:destination b)
                    " " (:departure-date b) " to " (:return-date b)
                    (when-not (:verified? b) "  [NOT KYC-verified]"))))
    (println))

  (println "Ready for deployment.")
  (println)
  0)
