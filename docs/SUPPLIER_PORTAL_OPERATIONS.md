# Supplier portal deployment and recovery

Use a dedicated Linux host or VM in the supplier network. The
[deployment examples](../src/supplier-portal/deploy/) assume systemd,
nftables, a public HTTPS listener on 8443 behind TCP forwarding, and a
mutual-TLS listener on 9443. Keep main SecMan and the portal on separate
identities, networks, and storage. The portal does not need a database
account, a cloud role, internet client access, or SMTP credentials.

## Deploy in staging

1. Build the portal in the trusted build environment with Go 1.26 or
   later. Install the reviewed binary as `/usr/local/bin/secman-supplier-portal`,
   owned by root. Create the dedicated service user with
   `systemd-sysusers` and the supplied sysusers file.
2. Place the unit in `/etc/systemd/system/` and copy the environment
   example to `/etc/secman-supplier/portal.env`. Keep
   `SUPPLIER_PUBLIC_PAUSED=true` during setup. Supply separate public and
   integration server certificates. Give the service identity read access
   to its own server keys and the CA certificate for the main SecMan
   client identity. Keep CA private keys and the main SecMan client key
   outside the portal host.
3. Include the supplied nftables table in the host ruleset. Replace its
   documentation address with main SecMan's observed egress IPv4 and
   match the configured integration port. Validate the complete ruleset
   with `nft -c -f /etc/nftables.conf` before activation. Limit the public
   edge to the public listener. On the integration listener, allow the
   main SecMan source IP at the firewall and require mutual TLS at the
   portal. The outbound rule allows the dedicated UID to answer
   established inbound TCP connections and drops its other IP traffic.
   Keep network-level egress denial around the portal host as an
   independent control. [nftables reference](https://netfilter.org/projects/nftables/manpage.html)
4. Load the unit and start it after the firewall rules are active. Use
   `systemd-analyze verify /etc/systemd/system/secman-supplier.service`
   and inspect the effective unit and ruleset. The unit runs with a
   dedicated state directory, an empty capability set, restricted
   filesystem access, and outbound connection syscalls denied.
   [systemd execution reference](https://freedesktop.org/software/systemd/man/latest/systemd.exec.html)
5. Configure main SecMan as described in the
   [portal README](../src/supplier-portal/README.md). Use a dedicated
   client certificate and a dedicated truststore for the integration
   server. Confirm that the server certificate covers the fixed
   integration IP. Enable supplier delivery on the staging backend and
   point SMTP at the TLS-enabled test mail sink.
6. Verify the integration listener and firewall before enabling the public
   listener. Set `SUPPLIER_PUBLIC_PAUSED=false` and restart the portal.

Keep the dedicated state volume and any backups encrypted and governed by the
approved retention schedule. Portal records can contain entire questionnaires
and answers. The portal has no automatic retention job. Apply the agreed
backup lifetime and retire state through the host's data-handling process.

## Pilot verification

Use synthetic questionnaires and a controlled staging mailbox. Record the
deployment revision, time, target environment, and sanitized test outcomes.
Avoid storing raw links, certificate keys, answer bodies, or delivery data in
the evidence.

| Check | Expected evidence |
|---|---|
| Integration boundary | From main SecMan's configured source, a valid client certificate reaches the integration route. A client without that certificate cannot complete the handshake. The public listener returns 404 for integration routes. |
| Portal outbound boundary | As the dedicated portal identity, attempt only pre-agreed connection checks to the staging backend, staging database and a staging control-plane canary. Confirm denial and the matching firewall counters. Verify from the actual service security context as well as at the host or network firewall. |
| Email-to-import | Mail sink accepts the synthetic invitation. Open the emailed link in a normal browser, save and resume, review, complete, and confirm the receipt. Watch the corresponding assessment show the same response revision as imported. |
| Supplier separation | Repeat for two suppliers and two engagements under the same supplier. A session or link from one invitation cannot act on another invitation. Repeating completion and import produces one internal revision. |
| Internal authority | Confirm that submission creates no acceptance or remediation. Confirm that the assigned reviewer can read the revision and an eligible independent human can accept it. |
| Containment and restore | Exercise intake pause, public pause, certificate rotation, revocation of an outstanding link after backup restore, and an incident hold on a previously accepted synthetic revision. Confirm original answers and decision history remain available internally. |

A passed application test proves application behavior on its fixture. Record
the separate evidence for the deployed firewall, service identity, and mail
provider before opening the pilot.

## Recover after compromise or a stale restore

1. Remove public ingress at the edge. Set `SUPPLIER_PUBLIC_PAUSED=true` on
   the rebuilt portal before exposing it. On **every** main SecMan instance,
   set `SECMAN_SUPPLIER_INTAKE_PAUSED=true` and restart. Confirm the old
   processes have exited. This stops polling and import while allowing
   main SecMan to deliver revocations over the integration listener.
   Disable `SECMAN_SUPPLIER_ENABLED` too if delivery must stop.
2. Preserve restricted evidence and record the suspected UTC window. Use
   main SecMan's invitation and import records as the source of assessment
   IDs, invitation IDs, observation and import dates, and imported revisions.
   Select records whose observed or imported times overlap the window.
   Do not use the portal's reported completion time as the sole basis for
   narrowing the window.
3. An authenticated human ADMIN can place a hold on each affected imported
   revision using
   `POST /api/risk-assessments/{assessmentId}/supplier-invitation/{invitationId}/incident-hold/{revision}`.
   The response is 204, including an identical retry. If the endpoint returns
   409, refresh status and verify that the revision is still in scope. The hold
   preserves answers, invalidates acceptance for that revision, and blocks
   another acceptance. Corrected or independently recollected answers belong
   in a new assessment. Assessors can see the hold in **Check Answers**.
4. Rotate the integration server and client identities and remove the old
   trust material from both ends. Rotate the public server keys as required.
   Rebuild from reviewed binaries. Prefer a fresh portal state directory after
   compromise. If restoring a stale copy, reconcile every outstanding and
   revoked invitation on the integration listener before opening public
   ingress. Replay previously confirmed revocations from main SecMan's
   records using the integration `PUT .../{invitationId}/revocation` and
   keep public access paused until they succeed. A backup can predate a
   revocation or contain an expired browser credential verifier.
5. Revoke and replace outstanding invitations selected for recollection.
   A fresh store avoids carrying forward drafts from a compromised system.
   Confirm old links and old client credentials fail in staging, and a new
   link can collect one clean submission.
6. Resume intake and public access after the deployment and recovery checks
   pass. Use the internal review view to confirm which records imported and
   which stayed on hold.
