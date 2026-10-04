# Security

SMS Relay handles one-time passwords and private messages. Please read this before relying on it.

## No warranty, not audited

This is a hobby project under the MIT licence, provided as is. Its design and code have been
reviewed by its authors but **not independently audited**. Use it at your own risk, and prefer
it only where you understand the threat model in the README.

## Reporting a vulnerability

Please report security issues privately through GitHub's
[private vulnerability reporting](../../security/advisories/new) for this repository, not in a
public issue. Include what you found, how to reproduce it, and the impact you expect. You will
get an acknowledgement as soon as possible; please allow time for a fix before disclosure.

## Scope

In scope: the Android app, the reader, the wire protocol (docs/PROTOCOL.md) and the deployment
advice in the README. Out of scope: vulnerabilities in the phone's operating system, the broker
service, or the vendored libraries themselves (report those upstream), and attacks that require
an unlocked phone in hand.
