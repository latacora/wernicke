# wernicke

A redaction tool. Run `clj -m latacora.wernicke` with some JSON on stdin.

Long keys are redacted:

    $ echo '{"some_key": "ABBBAAAABBBBAAABBBAABB"}' | clj -m 'latacora.wernicke.core'
    {"some_key":"NFbCFsVKzszOKFBC3LI0RG"}%

IPs, MAC addresses, timestamps, and a few other types of strings are redacted:

    $ echo '{"ip": "10.0.0.1", "mac": "ff:ff:ff:ff:ff:ff"}' | clj -m 'latacora.wernicke.core'
    {"ip":"200.225.40.176","mac":"00:de:c9:d8:d2:43"}

Redacted values are not consistent across runs:

    $ echo '{"ip": "10.0.0.1", "mac": "ff:ff:ff:ff:ff:ff"}' | clj -m 'latacora.wernicke.core'
    {"ip":"246.220.253.214","mac":"dc:08:90:75:e3:91"}

Redacted values _are_ consistent within runs:

    $ echo '{"ip": "10.0.0.1", "differentkeybutsameip": "10.0.0.1"}' | clj -m 'latacora.wernicke.core'
    {"ip":"250.6.62.252","differentkeybutsameip":"250.6.62.252"}

Named after Carl Wernicke, a German physician who did research on the brain.
Wernicke's aphasia is a condition where patients demonstrate fluent speech with
intact syntax but with nonsense words. This tool is kind of like that: the
resulting structure is maintained but all the words are swapped out with
nonsense.

## Installation

Download from https://github.com/latacora/wernicke

## Development

To run the project's tests:

    $ clj -A:test

## License

Copyright © 2019 Latacora, LLC. All rights reserved.
