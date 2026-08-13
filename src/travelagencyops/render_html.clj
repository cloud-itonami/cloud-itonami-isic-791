(ns travelagencyops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-791`: before
  this namespace the repo had NO demo page and no generator at all.

  Everything on the emitted page is produced by REALLY running this
  repo's own actor stack at build time --
  `travelagencyops.operation` (the compiled langgraph-clj StateGraph)
  -> `travelagencyops.governor` -> `travelagencyops.store` -- plus
  direct build-time calls into `travelagencyops.phase`,
  `travelagencyops.facts` and `travelagencyops.registry`. No status,
  hold reason, disposition or count on the page is hand-written:

    * the bookings table is the SSoT (`store/all-bookings`) *after*
      the scenario has run, with `facts/booking-verified?` and
      `registry/itinerary-complete?` evaluated live per row;
    * the governor matrix is measured by calling `governor/check`
      against a throwaway probe store, once per (op x rule) cell --
      it is not a description of the governor, it is the governor's
      answers;
    * the phase matrix is `phase/gate` evaluated for every
      (op x phase) pair;
    * the holds, ledger and decision-trail tables are the audit facts
      the graph actually emitted, including the governor's own
      Japanese `:detail` strings.

  The one thing the scenario supplies is the *inference layer*: the
  Advisor protocol in `travelagencyops.travelagencyopsllm` is
  explicitly documented as \"Injected at runtime (mock or real)\", and
  the shipped `mock-advisor` returns a constant `{:mock-data {}}` for
  every op, which (given the store contract measured below) would
  overwrite each booking record with that constant. `ScenarioAdvisor`
  below is a deterministic stand-in for a real advisor: it reads the
  current record out of the store, runs this repo's own
  `travelagencyops.registry` predicates over it, and proposes the
  merged record. Its confidence is *derived* from those predicates
  (an itinerary with no accommodations yet -> 0.42, below
  `governor/confidence-floor`), not asserted. Every disposition on the
  page is still the governor's and the phase gate's, never the
  advisor's.

  Measured store contract (2026-08-13, this repo, `clojure -M:dev`):
    * `store/commit-record!` writes `(:payload record)` -- NOT
      `(:value record)` -- so the approver added by the
      `:request-approval` node (`:approved-by`) IS retained in the
      SSoT record. The page nonetheless *checks* for the key at render
      time rather than assuming it, so it self-corrects if the store
      changes.
    * that write is a whole-record REPLACE, not a merge, so a proposal
      value that is not a complete record silently destroys the rest of
      the booking. This is why `ScenarioAdvisor` is read-modify-write.
    * the store ledger's `:committed` fact does not carry the approver
      (only the run's `:audit` channel `:approval-granted` fact does),
      so the ledger table below labels approver provenance explicitly.

  Determinism: no timestamps, no randomness, no unsorted map iteration
  in page content; two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [travelagencyops.facts :as facts]
            [travelagencyops.governor :as governor]
            [travelagencyops.operation :as op]
            [travelagencyops.phase :as phase]
            [travelagencyops.registry :as registry]
            [travelagencyops.store :as store]
            [travelagencyops.travelagencyopsllm :as llm]))

;; ─────────────────────────── scenario inputs ───────────────────────────

(def ^:private operator
  {:actor-id "op-1" :actor-role :travel-ops-coordinator :phase :phase-3})

(def ^:private approver "ops-lead-hana")

(def ^:private ops
  "This actor's closed operation set (README `Features`, `travelagencyops.sim`).
  The list is the actor's fixed vocabulary; everything the page says
  *about* each op is measured from `governor/check` and `phase/gate`."
  [:schedule-itinerary-booking
   :coordinate-booking-status-update
   :coordinate-supply-request
   :schedule-staff-shift-proposal
   :flag-safety-concern])

(def ^:private phases [:phase-0 :phase-1 :phase-2 :phase-3])

;; ─────────────────────────── the advisor ───────────────────────────
;; A deterministic stand-in for the injected inference layer. Reads the
;; store, runs the repo's own registry predicates, proposes the merged
;; record. Two of its five behaviours are deliberate advisor *drift*,
;; there to prove the governor is independent of it: the supply-request
;; branch bundles insurance underwriting into an otherwise valid supply
;; request, and the staff-shift branch tries to act directly
;; (`:effect :execute`) instead of proposing.

(defn- current [st request]
  (or (store/booking st (:subject request)) {:id (:subject request)}))

(deftype ScenarioAdvisor []
  llm/Advisor
  (-advise [_ st request]
    (let [subject (:subject request)
          rec (current st request)]
      (case (:op request)

        :schedule-itinerary-booking
        (let [proposed (merge rec {:status :confirmed
                                   :accommodations
                                   (str "3-night block held for " subject)})
              complete? (registry/itinerary-complete? proposed)]
          {:confidence (if complete? 0.92 0.40)
           :effect :propose
           :summary (str "Complete the " (:destination rec) " itinerary for " subject)
           :value proposed
           :cites (cond-> ["registry/booking-status-valid?"]
                    complete? (conj "registry/itinerary-complete?"))})

        :coordinate-booking-status-update
        (let [proposed (merge rec {:status :confirmed})
              valid? (registry/booking-status-valid? (:status proposed))
              ;; an itinerary with no accommodations on file is not yet
              ;; safe to call confirmed -- the advisor lowers its own
              ;; confidence and lets the governor route it to a human
              complete? (registry/itinerary-complete? proposed)]
          {:confidence (if complete? 0.88 0.42)
           :effect :propose
           :summary (str "Move " subject " to :confirmed")
           :value proposed
           :cites (cond-> [] valid? (conj "registry/booking-status-valid?")
                     complete? (conj "registry/itinerary-complete?"))})

        :coordinate-supply-request
        (let [req {:quantity 12
                   :description
                   "Airport transfer vouchers plus a travel insurance underwriting rider"}
              valid? (registry/supply-request-valid? req)]
          {:confidence (if valid? 0.90 0.30)
           :effect :propose
           :summary (str "Order tour supplies for " subject)
           :value (merge rec {:supply-request req})
           :cites (cond-> [] valid? (conj "registry/supply-request-valid?"))})

        :schedule-staff-shift-proposal
        (let [shift {:role "tour escort" :window (:departure-date rec)}
              conflict? (registry/staff-shift-conflicts? st shift)]
          ;; drift: writes the roster itself instead of proposing it
          {:confidence (if conflict? 0.20 0.90)
           :effect :execute
           :summary (str "Assign a tour escort for " subject)
           :value (merge rec {:staff-shift shift})
           :cites ["registry/staff-shift-conflicts?"]})

        :flag-safety-concern
        {:confidence 0.90
         :effect :propose
         :summary (str "Guide report filed against " subject)
         :value (merge rec {:concern {:report "Unlit stairwell reported at the trailhead lodge"
                                      :severity :medium}})
         :cites ["facts/booking-verified?"]}

        {:confidence 0.0 :effect :propose :summary "unknown op"
         :value rec :cites []}))))

;; ─────────────────────────── running the actor ───────────────────────────

(defn- run-step!
  "Fires one request through the real compiled graph. Returns the run result."
  [actor trail tid request]
  (let [res (g/run* actor {:request request :context operator} {:thread-id tid})]
    (swap! trail conj {:thread tid :request request :result res})
    res))

(defn- resolve!
  "Resumes an interrupted thread with a human decision. Throws if the
  thread is not actually parked at the human-in-the-loop interrupt --
  a silently-not-escalated op would otherwise render as if a human had
  seen it."
  [actor trail tid status]
  (let [prev (first (filter #(= tid (:thread %)) @trail))]
    (when-not (= :interrupted (get-in prev [:result :status]))
      (throw (ex-info "expected the thread to be parked for human approval"
                      {:thread tid :status (get-in prev [:result :status])})))
    (let [res (g/run* actor {:approval {:status status :by approver}}
                      {:thread-id tid :resume? true})]
      (swap! trail (fn [t] (mapv #(if (= tid (:thread %))
                                    (assoc % :result res :human status)
                                    %)
                                 t)))
      res)))

(defn run-demo!
  "Runs a fresh seeded store through a scenario that reaches every
  disposition this actor can produce.

    booking-001  itinerary completed (auto-commit, confidence derived
                 0.92 from registry/itinerary-complete?), then a status
                 update on the now-complete itinerary (auto-commit, 0.88)
    booking-002  status update with no accommodations on file -> advisor
                 confidence 0.42 < governor/confidence-floor -> escalated
                 -> approved by a human -> committed
    booking-003  registered but never KYC-verified -> HARD hold
                 :booking-record-unverified (never reaches a human)
    booking-004  supply request that drifted into insurance underwriting
                 -> HARD hold :scope-exclusion (never reaches a human)
    booking-005  staff-shift proposal where the advisor tried to act
                 (:effect :execute) -> HARD hold :effect-not-propose
                 (never reaches a human)
    booking-005  safety concern -> always high-stakes -> escalated ->
                 approved -> committed
    booking-004  safety concern -> escalated -> human REJECTED -> hold,
                 SSoT untouched

  Returns {:store st :trail [...]}."
  []
  (let [st (store/demo-store)
        actor (op/build st {:advisor (ScenarioAdvisor.)})
        trail (atom [])
        seeded (set (keys (store/all-bookings st)))]
    ;; every subject this scenario touches must actually exist in the
    ;; seed -- an op against a missing id holds for the wrong reason and
    ;; the page would report a governor decision that never happened.
    (doseq [s ["booking-001" "booking-002" "booking-003" "booking-004" "booking-005"]]
      (when-not (seeded s)
        (throw (ex-info "scenario subject is not in the seeded store"
                        {:subject s :seeded (vec (sort seeded))}))))

    (run-step! actor trail "t1-itinerary"
               {:op :schedule-itinerary-booking :subject "booking-001"})
    (run-step! actor trail "t1-status"
               {:op :coordinate-booking-status-update :subject "booking-001"})

    (run-step! actor trail "t2-status"
               {:op :coordinate-booking-status-update :subject "booking-002"})
    (resolve! actor trail "t2-status" :approved)

    (run-step! actor trail "t3-itinerary"
               {:op :schedule-itinerary-booking :subject "booking-003"})

    (run-step! actor trail "t4-supply"
               {:op :coordinate-supply-request :subject "booking-004"})

    (run-step! actor trail "t5-shift"
               {:op :schedule-staff-shift-proposal :subject "booking-005"})

    (run-step! actor trail "t5-safety"
               {:op :flag-safety-concern :subject "booking-005"})
    (resolve! actor trail "t5-safety" :approved)

    (run-step! actor trail "t4-safety"
               {:op :flag-safety-concern :subject "booking-004"})
    (resolve! actor trail "t4-safety" :rejected)

    {:store st :trail @trail}))

;; ─────────────── measuring the governor and the phase gate ───────────────

(def ^:private probe-clean {:confidence 0.9 :effect :propose
                            :value "Confirm the Lisbon itinerary"})

(defn- probe-store []
  (store/mem-store
   {"probe-ok"      {:id "probe-ok" :registered? true :verified? true}
    "probe-unkyc"   {:id "probe-unkyc" :registered? true :verified? false}}
   {}))

(defn- fired? [verdict rule]
  (boolean (some #(= rule (:rule %)) (:violations verdict))))

(defn measure-gates
  "Asks the real governor, one op at a time, what it does. Every cell of
  the rendered matrix is one `governor/check` call, not a description."
  []
  (let [st (probe-store)
        ctx {:actor-id "probe"}
        chk (fn [op subject proposal]
              (governor/check {:op op :subject subject} ctx proposal st))]
    (mapv (fn [o]
            (let [clean (chk o "probe-ok" probe-clean)
                  unkyc (chk o "probe-unkyc" probe-clean)
                  execd (chk o "probe-ok" (assoc probe-clean :effect :execute))
                  scope (chk o "probe-ok"
                             (assoc probe-clean :value
                                    "Bundle a travel insurance underwriting rider"))
                  lowc  (chk o "probe-ok" (assoc probe-clean :confidence 0.1))]
              {:op o
               :clean-ok? (:ok? clean)
               :high-stakes? (:high-stakes? clean)
               :kyc-gated? (fired? unkyc :booking-record-unverified)
               :effect-gated? (fired? execd :effect-not-propose)
               :scope-gated? (fired? scope :scope-exclusion)
               :low-conf-escalates? (:escalate? lowc)}))
          ops)))

(defn measure-phases
  "Runs `phase/gate` for every (op x phase) pair. The base disposition
  fed to the gate is not assumed clean -- it is `governor/check`'s real
  verdict for that op on a clean, high-confidence proposal against a
  verified record, put through `phase/verdict->disposition`. That
  matters: `:flag-safety-concern` is high-stakes, so its base is
  `:escalate`, and only then does the phase-2 safety carve-out show up."
  []
  (let [st (probe-store)
        ctx {:actor-id "probe"}]
    (mapv (fn [o]
            (let [verdict (governor/check {:op o :subject "probe-ok"} ctx probe-clean st)
                  base (phase/verdict->disposition verdict)]
              {:op o
               :base base
               :cells (mapv (fn [ph]
                              (let [{:keys [disposition reason]}
                                    (phase/gate ph {:op o} base)]
                                {:phase ph :disposition disposition :reason reason}))
                            phases)}))
          ops)))

;; ─────────────────────────── rendering ───────────────────────────

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- yn [b] (if b "<span class=\"warn\">yes</span>" "<span class=\"muted\">no</span>"))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- plural [n word] (if (= 1 n) word (str word "s")))

(defn- audit-facts
  "Every audit fact the run emitted, thread by thread, in scenario order."
  [trail]
  (vec (mapcat (fn [{:keys [thread result]}]
                 (map #(assoc % :thread thread) (get-in result [:state :audit])))
               trail)))

(defn- approval-granted-by
  "Approver recorded in the run's audit channel for a subject, if any."
  [facts subject]
  (some (fn [f] (when (and (= :approval-granted (:t f)) (= subject (:subject f)))
                  (:by f)))
        (reverse facts)))

(defn- approver-cell
  "Approver provenance for a booking, DERIVED -- checks whether the store
  actually retained `:approved-by` rather than assuming it does, so the
  page self-corrects if `store/commit-record!` changes. A booking that
  was never committed says so, rather than reading as if it had been
  auto-committed with nobody approving it."
  [rec ledger facts subject]
  (let [committed? (boolean (some #(and (= subject (:subject %)) (= :committed (:t %))) ledger))
        in-record (get rec :approved-by)
        in-audit (approval-granted-by facts subject)]
    (cond
      (some? in-record) (str "<span class=\"ok\">" (esc in-record) "</span>")
      (some? in-audit) (str "<span class=\"warn\">" (esc in-audit)
                            " (audit only &mdash; not retained in record)</span>")
      committed? "<span class=\"muted\">auto-committed &middot; no human approver</span>"
      :else "<span class=\"muted\">never committed</span>")))

(defn- last-ledger-fact [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- outcome-cell [ledger subject]
  (let [f (last-ledger-fact ledger subject)]
    (case (:t f)
      nil "<span class=\"muted\">no committed activity</span>"
      :committed (str "<span class=\"ok\">committed &middot; " (esc (kw (:op f))) "</span>")
      :governor-hold
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (str/join ", " (map kw (:basis f)))) "</span>")
      :approval-rejected
      (str "<span class=\"err\">human rejected &middot; " (esc (kw (:op f))) "</span>")
      (str "<span class=\"muted\">" (esc (kw (:t f))) "</span>"))))

(defn- booking-rows [st ledger facts]
  (let [clients (store/all-clients st)]
    (str/join "\n"
      (for [[id rec] (sort-by key (store/all-bookings st))]
        (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
                     "<td class=\"num\">%s &rarr; %s</td><td>%s</td><td>%s</td>"
                     "<td>%s</td><td>%s</td></tr>")
                (esc id)
                (esc (or (get-in clients [(:client-id rec) :name]) "—"))
                (esc (or (:destination rec) "—"))
                (esc (or (:departure-date rec) "—"))
                (esc (or (:return-date rec) "—"))
                (if (facts/booking-verified? rec)
                  "<span class=\"ok\">verified</span>"
                  "<span class=\"critical\">not KYC-verified</span>")
                (if (registry/itinerary-complete? rec)
                  "<span class=\"ok\">complete</span>"
                  "<span class=\"muted\">incomplete</span>")
                (esc (kw (or (:status rec) "—")))
                (str (approver-cell rec ledger facts id)
                     "<br>" (outcome-cell ledger id)))))))

(defn- gate-rows [gates]
  (str/join "\n"
    (for [{:keys [op clean-ok? high-stakes? kyc-gated? effect-gated?
                  scope-gated? low-conf-escalates?]} gates]
      (format (str "        <tr><td><code>:%s</code></td><td>%s</td><td>%s</td>"
                   "<td>%s</td><td>%s</td><td>%s</td></tr>")
              (esc (name op))
              (yn kyc-gated?)
              (yn effect-gated?)
              (if scope-gated? (yn true)
                  "<span class=\"muted\">exempt</span>")
              (yn low-conf-escalates?)
              (cond
                high-stakes? "<span class=\"warn\">always escalates &middot; high-stakes</span>"
                clean-ok? "<span class=\"ok\">auto-commit when clean</span>"
                :else "<span class=\"warn\">escalates</span>")))))

(defn- phase-rows [matrix]
  (str/join "\n"
    (for [{:keys [op base cells]} matrix]
      (str "        <tr><td><code>:" (esc (name op)) "</code></td>"
           "<td><code>" (esc (kw base)) "</code></td>"
           (str/join
            (for [{:keys [disposition reason]} cells]
              (str "<td>"
                   (if (= :commit disposition)
                     "<span class=\"ok\">commit</span>"
                     (str "<span class=\"warn\">" (esc (kw disposition)) "</span>"))
                   (when reason (str "<br><code>" (esc (kw reason)) "</code>"))
                   "</td>")))
           "</tr>"))))

(defn- hold-rows [ledger]
  (str/join "\n"
    (for [f (filter #(#{:governor-hold :approval-rejected} (:t %)) ledger)
          v (:violations f)]
      (format (str "        <tr><td><code>%s</code></td><td><code>:%s</code></td>"
                   "<td><code>%s</code></td><td>%s</td><td>%s</td></tr>")
              (esc (:subject f))
              (esc (name (:op f)))
              (esc (kw (:rule v)))
              (if (= :governor-hold (:t f))
                "<span class=\"critical\">HARD &middot; never reached a human</span>"
                "<span class=\"err\">human rejected the escalation</span>")
              (esc (or (:detail v) "—"))))))

(defn- ledger-rows [ledger]
  (str/join "\n"
    (map-indexed
     (fn [i f]
       (format (str "        <tr><td class=\"num\">%s</td><td>%s</td>"
                    "<td><code>:%s</code></td><td><code>%s</code></td>"
                    "<td>%s</td><td>%s</td></tr>")
               (inc i)
               (case (:t f)
                 :committed "<span class=\"ok\">committed</span>"
                 :governor-hold "<span class=\"critical\">governor-hold</span>"
                 :approval-rejected "<span class=\"err\">approval-rejected</span>"
                 (esc (kw (:t f))))
               (esc (name (:op f)))
               (esc (:subject f))
               (esc (str/join ", " (map kw (:basis f))))
               (esc (or (:summary f) ""))))
     ledger)))

(defn- trail-rows [trail]
  (str/join "\n"
    (for [{:keys [thread result human]} trail
          f (get-in result [:state :audit])]
      (format (str "        <tr><td><code>%s</code></td><td>%s</td>"
                   "<td><code>%s</code></td><td class=\"num\">%s</td><td>%s</td></tr>")
              (esc thread)
              (esc (kw (:t f)))
              (esc (or (:subject f) ""))
              (if (some? (:confidence f)) (esc (:confidence f)) "")
              (case (:t f)
                :advisor-proposal
                (str "effect <code>" (esc (kw (:effect f))) "</code>"
                     (when (and (some? (:confidence f))
                                (< (:confidence f) governor/confidence-floor))
                       (str " &middot; <span class=\"warn\">below confidence floor "
                            governor/confidence-floor "</span>")))
                :approval-requested
                (str "<span class=\"warn\">parked for a human</span> &middot; <code>"
                     (esc (kw (:reason f))) "</code>"
                     (if (contains? governor/high-stakes (:op f))
                       " &middot; high-stakes op"
                       (str " &middot; confidence " (esc (:confidence f))
                            " &lt; floor " governor/confidence-floor)))
                :approval-granted
                (str "<span class=\"ok\">approved by " (esc (:by f)) "</span>")
                :governor-hold
                (str "<span class=\"critical\">HARD hold &middot; "
                     (esc (str/join ", " (map kw (:basis f)))) "</span>")
                :approval-rejected
                (str "<span class=\"err\">"
                     (esc (kw (or human :rejected)))
                     " at the human gate</span> &middot; "
                     "<span class=\"muted\">the fact does not retain who rejected it</span>")
                :committed
                "<span class=\"ok\">written to the SSoT</span>"
                "")))))

(defn render
  "Renders the whole operator-console document from a completed run."
  [{:keys [store trail]}]
  (let [st store
        ledger (store/ledger st)
        facts (audit-facts trail)
        gates (measure-gates)
        phase-matrix (measure-phases)
        n-hard (count (filter #(= :governor-hold (:t %)) ledger))
        n-commit (count (filter #(= :committed (:t %)) ledger))
        n-rejected (count (filter #(= :approval-rejected (:t %)) ledger))
        n-approved (count (filter #(= :approval-granted (:t %)) facts))]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-791 &middot; travelagencyops operator console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Travel agency &amp; tour operator booking coordination (ISIC 791) — Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"badge\">read-only sample · governor-gated · generated by <code>clojure -M:dev:render-html</code></p>\n"
     "<p class=\"subtitle\">Every cell below is the output of really running "
     "<code>travelagencyops.operation</code> → <code>travelagencyops.governor</code> → "
     "<code>travelagencyops.store</code> at build time. Nothing is transcribed by hand.</p>\n"
     "<p class=\"banner\">This run: <strong class=\"num\">" n-commit "</strong> "
     (plural n-commit "commit") " · "
     "<strong class=\"num\">" n-approved "</strong> human " (plural n-approved "approval") " · "
     "<strong class=\"num\">" n-rejected "</strong> human " (plural n-rejected "rejection") " · "
     "<strong class=\"num\">" n-hard "</strong> HARD governor " (plural n-hard "hold")
     " that never reached a human.</p>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Bookings — SSoT after this run</h2>\n"
     "    <p class=\"muted\">Read back out of <code>travelagencyops.store</code> once the scenario "
     "has finished. <em>KYC</em> is <code>facts/booking-verified?</code> and <em>itinerary</em> is "
     "<code>registry/itinerary-complete?</code>, both evaluated on the row's live record.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Booking</th><th>Client</th><th>Destination</th><th>Travel window</th>"
     "<th>KYC</th><th>Itinerary</th><th>Status</th><th>Approver / last outcome</th></tr></thead>\n"
     "      <tbody>\n" (booking-rows st ledger facts) "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Governor gate — measured, one <code>governor/check</code> call per cell</h2>\n"
     "    <p class=\"muted\">The three HARD rules are asked directly: an op is probed against a "
     "registered-but-unverified booking record, against a proposal whose <code>:effect</code> is "
     "<code>:execute</code>, and against a proposal whose content drifts into excluded scope "
     "(visa / insurance / refund / authority override). HARD holds are not overridable and never "
     "reach a human. Confidence floor: <code class=\"num\">" governor/confidence-floor "</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>HARD: unverified record</th><th>HARD: effect ≠ :propose</th>"
     "<th>HARD: excluded scope</th><th>Low confidence escalates</th><th>Clean proposal</th></tr></thead>\n"
     "      <tbody>\n" (gate-rows gates) "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Rollout phase matrix — <code>phase/gate</code> for every op × phase</h2>\n"
     "    <p class=\"muted\">Each cell is the disposition the phase gate returns, with the reason "
     "code it attaches. The base disposition is not assumed: it is <code>governor/check</code>'s "
     "real verdict for that op on a clean high-confidence proposal against a verified record. "
     "This actor runs at <code>" (esc (kw (:phase operator))) "</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Governor verdict</th>"
     (str/join (for [p phases] (str "<th><code>" (esc (name p)) "</code></th>")))
     "</tr></thead>\n"
     "      <tbody>\n" (phase-rows phase-matrix) "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Holds this run</h2>\n"
     "    <p class=\"muted\">Reasons and detail text below are the governor's own output "
     "(<code>governor/hold-fact</code>), copied from the ledger verbatim.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Booking</th><th>Op</th><th>Rule</th><th>Kind</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n" (hold-rows ledger) "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger — append-only</h2>\n"
     "    <p class=\"muted\"><code>store/ledger</code> in append order. Only committed and held "
     "facts reach the ledger; the approval handshake lives in the run's audit channel below, so "
     "the approver of a committed row is read from the SSoT record, not from here.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Fact</th><th>Op</th><th>Booking</th><th>Basis</th><th>Summary</th></tr></thead>\n"
     "      <tbody>\n" (ledger-rows ledger) "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Decision trail — every audit fact the graph emitted</h2>\n"
     "    <p class=\"muted\">One row per fact on the graph's <code>:audit</code> channel, thread by "
     "thread, including the advisor proposals and the human-in-the-loop interrupts that the "
     "ledger does not keep.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Fact</th><th>Booking</th><th>Confidence</th><th>Detail</th></tr></thead>\n"
     "      <tbody>\n" (trail-rows trail) "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>travelagencyops.render-html</code> from "
     "<code>travelagencyops.store/demo-store</code>. Deterministic: no timestamps and no "
     "randomness in page content, so two consecutive runs are byte-identical. Regenerate with "
     "<code>clojure -M:dev:render-html</code>.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [store trail] :as run} (run-demo!)
        ledger (store/ledger store)
        facts (audit-facts trail)
        n-hard (count (filter #(= :governor-hold (:t %)) ledger))
        n-commit (count (filter #(= :committed (:t %)) ledger))
        n-approved (count (filter #(= :approval-granted (:t %)) facts))]
    ;; Build-time invariants, not comments: a console that shows no HARD
    ;; hold, or no clean commit, or no human-approved escalation, is not
    ;; demonstrating this actor's containment and must not be published.
    (when (zero? n-hard)
      (throw (ex-info "refusing to write the console: the run produced 0 :governor-hold records"
                      {:ledger-facts (count ledger) :commits n-commit})))
    (when (zero? n-commit)
      (throw (ex-info "refusing to write the console: the run produced 0 commits"
                      {:ledger-facts (count ledger)})))
    (when (zero? n-approved)
      (throw (ex-info "refusing to write the console: no escalation was approved by a human"
                      {:ledger-facts (count ledger)})))
    (spit out (render run) :encoding "UTF-8")
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, " (count facts) " audit facts, "
                  n-commit " commits, " n-approved " human approvals, "
                  n-hard " HARD governor holds)"))))
