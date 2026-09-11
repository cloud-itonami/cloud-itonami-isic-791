(ns travelagencyops.operation-graph-test
  "Graph-level coverage for travelagencyops.operation/build -- the actual
  compiled langgraph-clj StateGraph (governor_test.cljc only exercises
  travelagencyops.governor directly, never through g/run*)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [travelagencyops.operation :as op]
            [travelagencyops.store :as store]))

(defn- fresh []
  (let [st (store/demo-store)]
    [st (op/build st)]))

(def operator {:actor-id "op-1" :phase :phase-3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "op-1"}} {:thread-id tid :resume? true}))

(deftest commit-path-auto-commits
  (testing ":schedule-itinerary-booking on a verified booking, phase-3 -> auto-commit"
    (let [[st actor] (fresh)
          res (exec-op actor "t1"
                {:op :schedule-itinerary-booking :subject "booking-001"} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (some? (:mock-data (store/booking st "booking-001"))) "SSoT actually updated"))))

(deftest hard-hold-on-unverified-booking
  (testing "an unverified booking-record HARD-holds -- no auto-commit, no interrupt"
    (let [st (store/mem-store {"booking-bad" {:id "booking-bad" :registered? true :verified? false}} {})
          actor (op/build st)
          res (exec-op actor "t2"
                {:op :schedule-itinerary-booking :subject "booking-bad"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res))))))

(deftest escalate-then-approve-commits
  (testing ":flag-safety-concern is always high-stakes -> interrupted, then approve -> commit"
    (let [[st actor] (fresh)
          res1 (exec-op actor "t3"
                 {:op :flag-safety-concern :subject "booking-001"} operator)]
      (is (= :interrupted (:status res1)))
      (let [res2 (approve! actor "t3")]
        (is (= :commit (get-in res2 [:state :disposition])))
        (is (some? (:mock-data (store/booking st "booking-001"))) "SSoT actually updated")))))

(deftest escalate-then-reject-holds
  (testing ":flag-safety-concern interrupted, then reject -> hold, SSoT untouched"
    (let [[st actor] (fresh)
          before (store/booking st "booking-001")
          res1 (exec-op actor "t4"
                 {:op :flag-safety-concern :subject "booking-001"} operator)]
      (is (= :interrupted (:status res1)))
      (let [res2 (reject! actor "t4")]
        (is (= :hold (get-in res2 [:state :disposition])))
        (is (= before (store/booking st "booking-001")) "SSoT untouched on rejection")))))
