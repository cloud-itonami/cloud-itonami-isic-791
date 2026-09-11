(ns travelagencyops.governor-test
  (:require [travelagencyops.governor :as governor]
            [travelagencyops.store :as store]
            [clojure.test :refer [deftest is testing]]))

(deftest booking-record-check
  (testing "Unverified booking is rejected"
    (let [st (store/mem-store {"booking-bad" {:id "booking-bad" :registered? true :verified? false}} {})
          req {:op :schedule-itinerary-booking :subject "booking-bad"}
          ctx {:actor-id "test-actor"}
          proposal {:confidence 0.9 :effect :propose}
          result (governor/check req ctx proposal st)]
      (is (false? (:ok? result)))
      (is (seq (:violations result)))
      (is (= :booking-record-unverified (-> result :violations first :rule)))))

  (testing "Verified booking passes check"
    (let [st (store/demo-store)
          req {:op :schedule-itinerary-booking :subject "booking-001"}
          ctx {:actor-id "test-actor"}
          proposal {:confidence 0.9 :effect :propose}
          result (governor/check req ctx proposal st)]
      (is (true? (:ok? result)))
      (is (empty? (:violations result))))))

(deftest effect-check
  (testing "Non-propose effect is rejected"
    (let [st (store/demo-store)
          req {:op :schedule-itinerary-booking :subject "booking-001"}
          ctx {:actor-id "test-actor"}
          proposal {:confidence 0.9 :effect :execute}
          result (governor/check req ctx proposal st)]
      (is (false? (:ok? result)))
      (is (seq (:violations result)))
      (is (= :effect-not-propose (-> result :violations first :rule)))))

  (testing "Propose effect passes check"
    (let [st (store/demo-store)
          req {:op :schedule-itinerary-booking :subject "booking-001"}
          ctx {:actor-id "test-actor"}
          proposal {:confidence 0.9 :effect :propose}
          result (governor/check req ctx proposal st)]
      (is (true? (:ok? result))))))

(deftest scope-exclusion-check
  (testing "Visa content is rejected"
    (let [st (store/demo-store)
          req {:op :schedule-itinerary-booking :subject "booking-001"}
          ctx {:actor-id "test-actor"}
          proposal {:confidence 0.9 :effect :propose :value "Check visa eligibility"}
          result (governor/check req ctx proposal st)]
      (is (false? (:ok? result)))
      (is (seq (:violations result)))
      (is (= :scope-exclusion (-> result :violations first :rule)))))

  (testing "Insurance content is rejected"
    (let [st (store/demo-store)
          req {:op :schedule-itinerary-booking :subject "booking-001"}
          ctx {:actor-id "test-actor"}
          proposal {:confidence 0.9 :effect :propose :value "Travel insurance policy"}
          result (governor/check req ctx proposal st)]
      (is (false? (:ok? result)))
      (is (seq (:violations result)))
      (is (= :scope-exclusion (-> result :violations first :rule)))))

  (testing "Legitimate booking content passes"
    (let [st (store/demo-store)
          req {:op :schedule-itinerary-booking :subject "booking-001"}
          ctx {:actor-id "test-actor"}
          proposal {:confidence 0.9 :effect :propose :value "Schedule Tokyo itinerary"}
          result (governor/check req ctx proposal st)]
      (is (true? (:ok? result))))))

(deftest flag-safety-concern-escalates
  (testing "Flag safety concern is high-stakes and escalates"
    (let [st (store/demo-store)
          req {:op :flag-safety-concern :subject "booking-001"}
          ctx {:actor-id "test-actor"}
          proposal {:confidence 0.9 :effect :propose}
          result (governor/check req ctx proposal st)]
      (is (true? (:escalate? result)))
      (is (true? (:high-stakes? result))))))
