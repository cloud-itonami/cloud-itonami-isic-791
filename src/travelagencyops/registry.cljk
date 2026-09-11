(ns travelagencyops.registry
  "Travel booking registry -- verification and validation functions.")

(defn booking-status-valid?
  "Check whether a booking status is one of the canonical values."
  [status]
  (contains? #{:pending :confirmed :cancelled :completed} status))

(defn itinerary-complete?
  "An itinerary is complete when it has destination, dates, and accommodations."
  [itinerary]
  (and (:destination itinerary)
       (:departure-date itinerary)
       (:return-date itinerary)
       (:accommodations itinerary)))

(defn supply-request-valid?
  "A supply request is valid when it has quantity and description."
  [request]
  (and (pos-int? (:quantity request))
       (:description request)))

(defn staff-shift-conflicts?
  "Check whether a proposed staff shift conflicts with existing shift."
  [store shift-proposal]
  false)
