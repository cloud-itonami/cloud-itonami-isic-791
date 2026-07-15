(ns travelagencyops.facts
  "Ground-truth booking and client records.")

(defn booking-verified?
  "A booking is verified when registered and has passed KYC verification."
  [booking]
  (and (true? (:registered? booking))
       (true? (:verified? booking))))

(defn client-verified?
  "A client/traveler is verified when registered and identity-verified."
  [client]
  (and (true? (:registered? client))
       (true? (:verified? client))))
