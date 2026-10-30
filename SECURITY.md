# Security Policy

## Supported Versions

Security fixes go into the **latest release** only. There are no backports to older versions, so
upgrade to the current release to receive a fix.

| Version          | Supported          |
| ---------------- | ------------------ |
| Latest (2.24.x)  | :white_check_mark: |
| Older releases   | :x:                |

## Reporting a Vulnerability

**Please do not open a public issue for a security vulnerability.** Report it privately, in either
of these ways:

- **GitHub:** use [Report a vulnerability](https://github.com/surisoft-io/capi-core/security/advisories/new)
  on the repository's Security tab.
- **Email:** info@surisoft.io

Please include the CAPI version, the relevant configuration (with secrets removed), and steps to
reproduce. A proof of concept helps.

We aim to acknowledge a report within 5 working days and will keep you informed until it is
resolved. Once a fix is released, we are happy to credit you in the release notes unless you prefer
otherwise.

## Deployment assumptions

CAPI is designed to expose **only its gateway listeners** to untrusted networks. Reports that depend
on reaching the following from an untrusted network are about deployment configuration, not a
vulnerability in CAPI. They are still welcome if the documentation does not make this clear.

- The **admin listener** (`/info/*` and the trust-store endpoints).
- **Consul**, including its KV store, which CAPI treats as trusted configuration.
- **Hazelcast member ports** (5701 and up) used for clustering. Restrict them to other CAPI instances.
- The **OPA bundle server** and the **JWKS endpoints** CAPI fetches keys from.
