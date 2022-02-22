;; Copyright © 2022, JUXT LTD.

(ns juxt.pass.alpha.authorization
  (:require
   [xtdb.api :as xt]
   [clojure.tools.logging :as log]))

(alias 'http (create-ns 'juxt.http.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))
(alias 'site (create-ns 'juxt.site.alpha))

(defn rules
  "Construct rules from a ruleset id"
  [db ruleset]
  (assert ruleset)
  (assert (string? ruleset))
  (->>
   (xt/q
    db
    '{:find [rule-content]
      :where [[ruleset ::pass/rules rule]
              [rule ::pass/rule-content rule-content]]
      :in [ruleset]}
    ruleset)
   (map (comp read-string first))
   (mapcat seq)
   vec))

(defn acls
  "Return any ACLs that grant access to the given resource. The subject is derived
  from the session at the beginning of a request. The session may represent
  additional context over and above the subject, for example, additional claims,
  the access scope and method of authentication. Both the session and subject
  arguments can be nil, the resource argument must not be."
  [db subject session action resource]

  ;; Subject can be nil, resource cannot be
  (assert (or (nil? subject) (string? subject)))
  (assert (or (nil? session) (string? session)))
  (assert (string? action))
  (assert (string? resource))

  (let [rules (when-let [ruleset (::pass/ruleset (xt/entity db resource))]
                (rules db ruleset))
        query {:find ['(pull acl [*])]
               :where '[[acl ::site/type "ACL"]
                        (check acl subject session action resource)]
               :rules rules
               :in '[subject session action resource]}]
    (if (seq rules)
      (map first (xt/q db query subject session action resource))
      #{})))

(defn list-resources
  [db subject session ruleset]
  (assert (string? session))
  (assert (string? ruleset))
  (let [rules
        (->>
         (xt/q
          db
          '{:find [rule-content]
            :where [[ruleset ::pass/rules rule]
                    [rule ::pass/rule-content rule-content]]
            :in [ruleset]}
          ruleset)
         (map (comp read-string first))
         (mapcat seq)
         vec)

        query {:find ['(pull acl [*])]
               :where '[[acl ::site/type "ACL"]
                        (list-resources acl subject session)]
               :rules rules
               :in '[subject session]}]

    (if (seq rules)
      (map first (xt/q db query subject session))
      #{})))

(defn get-subject-from-session
  "Return the subject id from the session id."
  [db ruleset session]
  (let [rules (rules db ruleset)]
    (ffirst
     (xt/q db {:find '[subject]
               :where '[(get-subject-from-session session subject)]
               :rules rules
               :in '[session]} session))))

(defn authorize-resource [{::site/keys [db uri]
                           :ring.request/keys [method]
                           ::pass/keys [session] :as req}]
  (let [acls (acls
              db
              (:xt/id session)

              ;; TODO: The subject needs to be in the database, accessible to Datalog, and for
              ;; historic/audit. Therefore, the subject needs to be established when a session
              ;; is created (access with a session id cookie), or when a Bearer token is
              ;; minted. Whatever the means of access, the same subject needs to be determined
              ;; (whether accessed via session id, bearer token or other means).

              (case method :get "read")
              uri)]
    (log/tracef "acls are %s" acls)
    (cond-> req
      (seq acls) (assoc-in [::site/resource ::pass/authorization ::pass/acls] acls)
      ;;true (assoc-in [::site/resource ::pass/authorization] {:debug true})
      )))
