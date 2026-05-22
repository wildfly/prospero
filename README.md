## Prospero 
Prospero is a tool combining Galleon feature-packs and wildfly-channels to provision 
and update Wildfly server.

## Example:
The demo below provisions and updates Wildfly 29.0.0.Final.

1. Build prospero
   ```
      cd <PROSPERO_HOME>
      mvn clean install
   ```
2. Provision server
   ```
      ./prospero install --profile=wildfly --dir=wfly-29 --channel=examples/wildfly-29.0.0.Final-channel.yaml
   ```
3. Update server
   1. Edit `examples/wildfly-29.0.0.Final-manifest.yaml` (configured in the wildfly-29.0.0.Final-channel.yaml) and update undertow-core version to:
   ```
      - groupId: "io.undertow"
        artifactId: "undertow-core"
        version: "2.3.8.Final"
   ```
   2. Update server
   ```
      ./prospero update perform --dir=wfly-29
   ```

## Contributing
Please see the instructions available in the [contribution guide](CONTRIBUTING.md).

## Building distribution
The full distribution of Prospero includes a Galleon feature-pack, a standalone zip and documentation. Building those projects is excluded by default and enabled only if a maven `dist` profile is enabled.
```
   cd <PROSPERO_HOME>
   mvn clean install -Pdist
```

## Running integration tests
Slower tests (e.g. including provisioning a full server), are located in integration-tests directory and are enabled by `-DallTests` property.
```
   cd <PROSPERO_HOME>
   mvn clean install -DallTests
```
## Configuring log level

Prospero uses a logging configuration file named `prospero-logging.properties`.

This file defines the log levels used by Prospero and its dependencies.  

Located in: `<PROSPERO_HOME>/dist/build/target/prospero-<version>-SNAPSHOT/bin`

To adjust the verbosity, edit the log level entries:

`logger.org.wildfly.prospero.level=INFO`

`logger.com.networknt.schema.level=INFO`

`logger.org.eclipse.aether.internal.impl.level=INFO`

**Root logger level (applies to all loggers not explicitly configured)**
`logger.level=INFO`

Valid log levels are:

`ERROR, WARN, INFO, DEBUG, TRACE`

**Example: Enable `DEBUG` logs**

To enable detailed logs for Prospero:

`logger.org.wildfly.prospero.level=DEBUG`

To apply `DEBUG` logging globally:

`logger.level=DEBUG`

**Console and File Logging**

Prospero logs to both the console and a file. Their log levels can be configured using:

`handler.CONSOLE.level=WARN`

`handler.FILE.level=DEBUG`

**Using --debug for temporary detailed logs**

You can also enable detailed `DEBUG` logs temporarily by adding the `--debug` option to any Prospero command.  
This does **not require editing** the `prospero-logging.properties` file.

Example:

`./prospero install --profile=wildfly --dir=wfly-29 --channel=examples/wildfly-29.0.0.Final-channel.yaml --debug`

License
-------
* [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0)