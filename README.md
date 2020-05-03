# wernicke

<img alt="Carl Wernicke" src="https://raw.githubusercontent.com/latacora/wernicke/master/carl.jpg" align="right">

![CI](https://github.com/latacora/wernicke/workflows/CI/badge.svg)

A redaction tool. Run `wernicke` with JSON on stdin, get redacted values out.
Preserves structure and (to some extent) semantics. You might want this because
you have test data where the actual values are sensitive. Because the changes
are consistent within the data, and the overall data structure is preserved
there's less chance this will make your data unsuitable for testing purposes.

Most people run wernicke on a shell, so you either have `json_producing_thing |
wernicke` or `wernicke < some_file.json > redacted.json`.

(These examples are pretty-printed for viewing comfort, but wernicke does not do that for you. Try [jq](https://stedolan.github.io/jq/).)

<table>

<tr>
<th>Example input</th>
<th>Example output</th>
</tr>

<tr></tr>

<tr>
<td colspan="2">
Long (>12 chars) keys are redacted.
</td>
</tr>

<tr>
<td>

```json
{"some_key": "ABBBAAAABBBBAAABBBAABB"}
```

</td>
<td>

```json
{"some_key": "teyjdaeqEYGw18fRIt5vLo"}
```

</td>
</tr>

<tr>
<td colspan="2">
IPs, MAC addresses, timestamps, various AWS identifiers, and a few other types of strings are redacted to strings of the same type: IPs to IPs, SGs to SGs, et cetera. If these strings have an alphanumeric id, that id will have the same length.
</td>
</tr>

<tr>
<td>

```json
{
  "ip": "10.0.0.1",
  "mac": "ff:ff:ff:ff:ff:ff",
  "timestamp": "2017-01-01T12:34:56.000Z",
  "ec2_host": "ip-10-0-0-1.ec2.internal",
  "security_group": "sg-12345",
  "vpc": "vpc-abcdef",
  "aws_access_key_id": "AKIAXXXXXXXXXXXXXXXX",
  "aws_role_cred": "AROAYYYYYYYYYYYYYYYY"
}
```

</td>

<td>

```json
{
  "ip": "254.65.252.245",
  "mac": "aa:3e:91:ab:3b:3a",
  "timestamp": "2044-19-02T20:32:55.72Z",
  "ec2_host": "ip-207-255-185-237.ec2.internal",
  "security_group": "sg-887b8",
  "vpc": "vpc-a9d96a",
  "aws_access_key_id": "AKIAQ5E7IHRMOW7YABLS",
  "aws_role_cred": "AROA6QA7SQTM6YWS4F0H"
}
```

</td>
</tr>

<td colspan="2">
Redaction happens in arbitrarily nested structures.
</td>

<tr>
<td>

```json
{
  "a": {
    "b": [
      "c",
      "d",
      {
        "e": "10.0.0.1"
      }
    ]
  }
}
```

</td>

<td>

```json
{
  "a": {
    "b": [
      "c",
      "d",
      {
        "e": "1.212.241.246"
      }
    ]
  }
}
```

</td>
</tr>

<td colspan="2">
The redacted values will change across runs (this is necessary to make redaction
irreversible).
</td>

<tr>
<td>

```json
{
  "ip": "10.0.0.1",
  "mac": "ff:ff:ff:ff:ff:ff"
}
```

</td>
<td>

```json
{
    "ip":"246.220.253.214",
    "mac":"dc:08:90:75:e3:91"
}
```

</td>
</tr>

<td colspan="2">
Redacted values _do_ stay consistent within runs. If the input contains the same
value multiple times it will get redacted identically. This allows you to still do correlation in the result.
</td>

<tr>
<td>

```json
{
  "ip": "10.0.0.1",
  "different_key_but_same_ip": "10.0.0.1"
}
```

</td>
<td>

```json
{
  "ip": "247.226.167.9",
  "different_key_but_same_ip": "247.226.167.9"
}
```

</td>
</tr>
</table>

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
