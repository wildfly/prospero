# Contributing guide

**First off all, thank you for taking the time to contribute into Prospero!** The below contents will help you through the steps for getting started with Prospero. Please make sure to read the relevant section before making your contribution. It will make it a lot easier for us maintainers and smooth out the experience for all involved. The community looks forward to your contributions.

## Documentation

You can find all information and examples in [Prospero documentation](https://docs.wildfly.org/prospero/)

## Development environment

* Maven 3.9.x build tool https://maven.apache.org/, other versions may work too
* JDK 17+ You can use any distribution
* [Eclipse](https://www.eclipse.org/downloads/packages/) or any other IDE allowing correct source formatting (IntelliJ)

### Maven
---

#### Build prospero

```
    cd <PROSPERO_HOME>
    mvn clean install
```

#### Building distribution

The full distribution of Prospero includes a Galleon feature pack, a standalone zip and documentation. Building those projects is excluded by default and enabled only if a maven `dist` profile is enabled.

```
   cd <PROSPERO_HOME>
   mvn clean install -Pdist
```

#### Running integration tests

Slower tests (e.g. including provisioning a full server), are located in integration-tests directory and are enabled by `-DallTests` property.
```
   cd <PROSPERO_HOME>
   mvn clean install -DallTests
```

### IDE Integration
---

#### Eclipse
The "formal" rules to format code are based on Eclipse. You can find them here [WildFly Core IDE Eclipse Configuration](https://github.com/wildfly/wildfly-core/tree/main/ide-configs/eclipse)

1. in Eclipse go to Window -> Preferences -> Java -> Code Style -> Formatter.
2. click Import.
3. select formatting rules which you have downloaded

**Same for cleanup and templates**

#### IntelliJ IDEA
##### Code Formatter
There is a plugin for IntelliJ to use the Eclipse formatter: [Eclipse Code Formatter](https://github.com/krasa/EclipseCodeFormatter#instructions)

##### Import Prospero as Maven project in IntelliJ IDEA
Before importing you have to set the VM Options of the IntelliJ Maven importer. Please [follow this guide](https://www.jetbrains.com/help/idea/maven-importing.html) and add ```-DallTests``` to the field ```VM options for importer```.
This is necessary because the testsuite poms do not contain all necessary modules by default.

### Issues
---

You can find all issues under [Github Issues](https://github.com/wildfly/prospero/issues) page. Once you have selected an issue you'd like to work on, make sure it's not already assigned to someone else.

Check out our issues with the `good-first-issue` label. These are a triaged set of issues that are great for getting started on our project.

**Lastly, this project is an open source project. Please act responsibly, be nice, polite and enjoy!**