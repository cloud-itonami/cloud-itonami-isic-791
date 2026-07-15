(ns travelagencyops.store
  "TravelAgencyOps store abstraction -- in-memory MemStore for development.")

(defprotocol Store
  (booking [this booking-id] "Retrieve a booking record by ID")
  (client [this client-id] "Retrieve a client record by ID")
  (commit-record! [this record] "Write a booking/client update to the SSoT")
  (append-ledger! [this fact] "Append an audit fact to the append-only ledger"))

(deftype MemStore [bookings clients ledger]
  Store
  (booking [this booking-id]
    (get @bookings booking-id))
  (client [this client-id]
    (get @clients client-id))
  (commit-record! [this record]
    (when (:path record)
      (let [[entity-id] (:path record)]
        (swap! bookings assoc entity-id (:payload record)))))
  (append-ledger! [this fact]
    (swap! ledger conj fact)))

(defn mem-store
  "Create an in-memory MemStore for dev/testing."
  [initial-bookings initial-clients]
  (MemStore. (atom initial-bookings)
             (atom initial-clients)
             (atom [])))

(def demo-booking
  {:id "booking-001"
   :client-id "client-001"
   :registered? true
   :verified? true
   :status :pending
   :destination "Tokyo"
   :departure-date "2026-08-01"
   :return-date "2026-08-08"})

(def demo-client
  {:id "client-001"
   :name "Alice Chen"
   :registered? true
   :verified? true
   :email "alice@example.com"})

(defn demo-store
  "Create a demo MemStore with sample data."
  []
  (mem-store {"booking-001" demo-booking}
             {"client-001" demo-client}))
