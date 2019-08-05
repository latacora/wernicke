# wernicke

A redaction tool. Run `wernicke` with some JSON on stdin.

Long (>12) keys are redacted:

    $ echo '{"some_key": "ABBBAAAABBBBAAABBBAABB"}' | wernicke
    {"some_key":"NFbCFsVKzszOKFBC3LI0RG"}

IPs, MAC addresses, timestamps, and a few other types of strings are redacted:

    $ echo '{"ip": "10.0.0.1", "mac": "ff:ff:ff:ff:ff:ff"}' | wernicke
    {"ip":"200.225.40.176","mac":"00:de:c9:d8:d2:43"}

Redaction happens in arbitrarily deeply nested structures:

    $ echo '{"a": {"b": ["c", "d", {"e": "10.0.0.1"}]"}}}' | wernicke
    {"a":{"b":["c","d",{"e":"252.252.233.18"}]}}

Redacted values are not consistent across runs:

    $ echo '{"ip": "10.0.0.1", "mac": "ff:ff:ff:ff:ff:ff"}' | wernicke
    {"ip":"246.220.253.214","mac":"dc:08:90:75:e3:91"}

Redacted values _are_ consistent within runs:

    $ echo '{"ip": "10.0.0.1", "differentkeybutsameip": "10.0.0.1"}' | wernicke
    {"ip":"250.6.62.252","differentkeybutsameip":"250.6.62.252"}

Named after Carl Wernicke, a German physician who did research on the brain.
Wernicke's aphasia is a condition where patients demonstrate fluent speech with
intact syntax but with nonsense words. This tool is kind of like that: the
resulting structure is maintained but all the words are swapped out with
(internally consistent) nonsense.

You might want this because you have test data where the actual values are
sensitive. Because the changes are consistent within the data, there's less
chance this will ruin your data for testing purposes.

## Installation

Download from https://github.com/latacora/wernicke

## Development

To run the project directly from a source checkout:

    $ clj -m latacora.wernicke

To run the project's tests:

    $ clj -A:test

To build a native image:

    $ clj -A:native-image

(This requires GraalVM to be installed with SubstrateVM, and the `GRAAL_HOME`
environment variable to be set.)

## Future plans

- Configurable redactions

## License

Copyright © 2019 Latacora, LLC. All rights reserved.
