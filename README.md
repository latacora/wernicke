# wernicke

<img alt="Carl Wernicke" src="https://raw.githubusercontent.com/latacora/wernicke/master/carl.jpg" align="right">

![CI](https://github.com/latacora/wernicke/workflows/CI/badge.svg)

A redaction tool. Run `wernicke` with JSON on stdin, get redacted values out.
Preserves structure and (to some extent) semantics. You might want this because
you have test data where the actual values are sensitive. Because the changes
are consistent within the data, and the overall data structure is preserved
there's less chance this will make your data unsuitable for testing purposes.

## Examples

Long (>12) keys are redacted:

    $ echo '{"some_key": "ABBBAAAABBBBAAABBBAABB"}' | wernicke
    {"some_key":"NFbCFsVKzszOKFBC3LI0RG"}

IPs, MAC addresses, timestamps, and a few other types of strings are redacted:

    $ echo '{"ip": "10.0.0.1", "mac": "ff:ff:ff:ff:ff:ff"}' | wernicke
    {"ip":"200.225.40.176","mac":"00:de:c9:d8:d2:43"}

Redaction happens in arbitrarily deeply nested structures:

    $ echo '{"a": {"b": ["c", "d", {"e": "10.0.0.1"}]"}}}' | wernicke
    {"a":{"b":["c","d",{"e":"252.252.233.18"}]}}

Redacted values change across runs (this is necessary to make redaction
irreversible):

    $ echo '{"ip": "10.0.0.1", "mac": "ff:ff:ff:ff:ff:ff"}' | wernicke
    {"ip":"246.220.253.214","mac":"dc:08:90:75:e3:91"}

Redacted values _are_ consistent within runs. This means a large data structure
that contains the same value multiple times gets redacted identically, and you
can still correlate in the result.

    $ echo '{"ip": "10.0.0.1", "differentkeybutsameip": "10.0.0.1"}' | wernicke
    {"ip":"250.6.62.252","differentkeybutsameip":"250.6.62.252"}

## Installation

Download from https://github.com/latacora/wernicke/releases

## Development

To run the project directly from a source checkout:

    $ clj -m latacora.wernicke.cli

To run the project's tests:

    $ clj -A:test

To build a native image:

    $ clj -A:native-image

(This requires GraalVM to be installed with SubstrateVM, and the `GRAAL_HOME`
environment variable to be set.)

## Namesake

Named after Carl Wernicke, a German physician who did research on the brain.
Wernicke's aphasia is a condition where patients demonstrate fluent speech with
intact syntax but with nonsense words. This tool is kind of like that: the
resulting structure is maintained but all the words are swapped out with
(internally consistent) nonsense.

## License

Copyright © Latacora, LLC

This program and the accompanying materials are made available under the terms
of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.
