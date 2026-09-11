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

;; ── read side ───────────────────────────────────────────────────────────
;; The Store protocol is the *write* contract (what the actor's :commit and
;; :hold nodes call). Reporting consumers -- `travelagencyops.render-html`,
;; `travelagencyops.sim`, tests -- need to read the whole SSoT and the
;; append-only journal back out, which the protocol deliberately does not
;; expose per-entity. These are plain functions over the MemStore fields so
;; the protocol (and every implementation of it) stays untouched.

(defn all-bookings
  "Every booking record currently in the SSoT, as {id record}."
  [st]
  @(.-bookings st))

(defn all-clients
  "Every client record currently in the SSoT, as {id record}."
  [st]
  @(.-clients st))

(defn ledger
  "The append-only audit journal, in append order."
  [st]
  (vec @(.-ledger st)))

;; ── demo seed ───────────────────────────────────────────────────────────
;; Ground truth for `demo-store`. Five bookings across five clients:
;; booking-003 is deliberately registered-but-not-KYC-verified so the
;; governor's :booking-record-unverified HARD check has something real to
;; fire on, and no booking is seeded with :accommodations so that
;; `travelagencyops.registry/itinerary-complete?` starts false for all of
;; them (an advisor has to actually complete an itinerary to make it true).

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

(def demo-bookings
  {"booking-001" demo-booking
   "booking-002" {:id "booking-002"
                  :client-id "client-002"
                  :registered? true
                  :verified? true
                  :status :pending
                  :destination "Lisbon"
                  :departure-date "2026-09-14"
                  :return-date "2026-09-21"}
   ;; registered, but KYC never completed -> HARD :booking-record-unverified
   "booking-003" {:id "booking-003"
                  :client-id "client-003"
                  :registered? true
                  :verified? false
                  :status :pending
                  :destination "Reykjavik"
                  :departure-date "2026-10-02"
                  :return-date "2026-10-09"}
   "booking-004" {:id "booking-004"
                  :client-id "client-004"
                  :registered? true
                  :verified? true
                  :status :pending
                  :destination "Cusco"
                  :departure-date "2026-11-05"
                  :return-date "2026-11-15"}
   "booking-005" {:id "booking-005"
                  :client-id "client-005"
                  :registered? true
                  :verified? true
                  :status :confirmed
                  :destination "Kyoto"
                  :departure-date "2026-12-20"
                  :return-date "2026-12-27"}})

(def demo-clients
  {"client-001" demo-client
   "client-002" {:id "client-002" :name "Marcus Oyelaran"
                 :registered? true :verified? true :email "marcus@example.com"}
   "client-003" {:id "client-003" :name "Priya Raghavan"
                 :registered? true :verified? false :email "priya@example.com"}
   "client-004" {:id "client-004" :name "Tomas Delgado"
                 :registered? true :verified? true :email "tomas@example.com"}
   "client-005" {:id "client-005" :name "Hanna Virtanen"
                 :registered? true :verified? true :email "hanna@example.com"}})

(defn demo-store
  "Create a demo MemStore with sample data."
  []
  (mem-store demo-bookings demo-clients))
