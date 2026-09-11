(ns travelagencyops.travelagencyopsllm
  "TravelAgencyOps Advisor -- LLM inference layer. Sealed in :advise node.
  Injected at runtime (mock or real).")

(defprotocol Advisor
  (-advise [this store request]
    "Generate a proposal. Returns {:confidence c :effect :propose :summary \"...\" :value {...} :cites [...]}"))

(defn trace
  "Audit trace for an advisor proposal."
  [request proposal]
  {:t :advisor-proposal
   :op (:op request)
   :subject (:subject request)
   :confidence (:confidence proposal)
   :effect (:effect proposal)})

(deftype MockAdvisor []
  Advisor
  (-advise [this store request]
    {:confidence 0.85
     :effect :propose
     :summary (str "Mock advisor proposing " (:op request) " for " (:subject request))
     :value {:mock-data {}}
     :cites ["mock-policy-001"]}))

(defn mock-advisor
  "Create a mock advisor for testing."
  []
  (MockAdvisor.))
