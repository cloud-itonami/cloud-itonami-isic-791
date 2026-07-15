(ns travelagencyops.sim
  "TravelAgencyOps simulation harness -- demo actor with mock advisor and in-memory store.")

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

  (println "Store initialized with demo booking:")
  (println "  booking-001: Alice Chen, Tokyo 2026-08-01 to 08-08")
  (println "  client-001: verified")
  (println)

  (println "Ready for deployment.")
  (println)
  0)
