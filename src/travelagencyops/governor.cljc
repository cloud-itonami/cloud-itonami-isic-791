(ns travelagencyops.governor
  "TravelAgencyOps Governor -- independent compliance layer. Three HARD checks:
  1. Booking/client-record verified (registered? + verified?)
  2. Effect is :propose (rejected outright otherwise)
  3. Scope exclusion (no visa/insurance/refund/authority overrides)")
  (:require [travelagencyops.facts :as facts]
            [travelagencyops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes requiring human oversight: flagging safety concerns always escalates."
  #{:flag-safety-concern})

(defn- booking-record-unverified-violations
  "Booking must exist in store AND be independently verified."
  [{:keys [op subject]} st]
  (when (contains? #{:schedule-itinerary-booking :coordinate-booking-status-update
                     :coordinate-supply-request} op)
    (let [booking (store/booking st subject)
          verified? (and booking (facts/booking-verified? booking))]
      (when-not verified?
        [{:rule :booking-record-unverified
          :detail (str subject " は登録・確認済みの予約レコードではない")}]))))

(defn- effect-not-propose-violations
  "Effect must be :propose -- all other effects rejected outright."
  [{:keys [op]} proposal]
  (when (not= (:effect proposal) :propose)
    [{:rule :effect-not-propose
      :detail "提案の:effectは:proposeである必要がある"}]))

(defn- scope-exclusion-violations
  "Reject proposals touching visa/insurance/refund/authority overrides.
  Exception: :flag-safety-concern always escalates, never self-blocks."
  [{:keys [op]} proposal]
  (when (not= op :flag-safety-concern)
    (let [content (str (pr-str proposal))
          forbidden-patterns
          [#"(?i)(visa|immigration|eligibility)"
           #"(?i)(insurance|liability|underwriting)"
           #"(?i)(refund|cancellation|cancellation-policy)"
           #"(?i)(safety.{0,10}authority|authority.{0,10}override)"
           #"(ビザ|査証|入国)"
           #"(保険|責任|過失)"
           #"(払戻|キャンセル|返金)"
           #"(安全当局|安全|権限)"]
          matches? (some #(re-find % content) forbidden-patterns)]
      (when matches?
        [{:rule :scope-exclusion
          :detail "提案がビザ/保険/返金/権限決定を含む（これらは人間レビュー対象）"}]))))

(defn check
  "Censors a proposal against the governor rules."
  [request _context proposal st]
  (let [hard (into []
                   (concat (effect-not-propose-violations request proposal)
                           (scope-exclusion-violations request proposal)
                           (booking-record-unverified-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "Audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
