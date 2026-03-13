# Usage Examples

This file shows how to use the Prospero CLI tool to create an installation of the Wildfly application server. These instructions
are provided for development and demonstration purposes only. In practice, no channels are expected to be published for
Wildfly (upstream project), and the Prospero tool would only be used to provision the JBoss EAP (downstream product).

Prospero needs two required inputs, to be able to provision an installation:

* a Galleon *feature pack location*,
* a *Wildfly Channel* reference.

## Dictionary of Terms
<dl>
    <dt>Galleon Feature Pack</dt>
    <dd>
        The <i>Galleon feature pack</i> is a package containing all the metadata required to compose an installation of the Wildfly 
        or JBoss EAP application server. Among other things, the feature pack contains coordinates (GAVs) to Maven artifacts
        that the Wildfly server is composed of.
    </dd>
    <dt>Wildfly Channel</dt>
    <dd>
        The <i>Wildfly Channel</i> is another bit of metadata. It's composed of two things: a reference to a
        <i>Wildfly Channel Manifest</i>, and a list of Maven repositories where above Maven artifacts can be found.
    </dd>
    <dt>Wildfly Channel Manifest</dt>
    <dd>
        The <i>Wildfly Channel Manifest</i> specifies versions of all the Maven artifacts that given feature
        pack depends on (these version override the original Maven artifacts versions defined in the feature pack).
    </dd>
</dl>

### Wildfly Channels & Manifests

Typically, the Wildfly Channels and Wildfly Channel Manifests would be distributed as well in a form of Maven artifacts and they
would be referenced via Maven coordinates (GAVs). This directory contains sample channel and manifest files to use when no
channels / manifests have been officially published yet:

* [wildfly-core-channel.yaml](wildfly-core-channel.yaml),
* [wildfly-core-manifest.yaml](wildfly-core-manifest.yaml).

(Note: These files have been generated for Wildfly Core version 20.0.0.Beta2.)

These files can be used to provision a Wildfly Core installation in following ways:

## Pre-requisite: Build Prospero

```shell
cd $PROSPERO_SOURCE_ROOT/
mvn clean package
```

## Installation of a Pre-Defined Product Version

```shell
./prospero install --profile wildfly \
  --manifest examples/wildfly-27.0.0.Alpha2-manifest.yaml \
  --dir installation-dir
```

(Note: the `--manifest ...` option would be optional for predefined "eap-*" options.)

## Installation Referencing a Channel

```shell
./prospero install --fpl org.wildfly.core:wildfly-core-galleon-pack:20.0.0.Beta3 \
  --channels examples/wildfly-core-channel.yaml \
  --dir installation-dir
```

Description:
 * The `--fpl` option defines a feature pack to be installed.
 * The `--channels` option defines a Wildfly Channel (a *Wildfly Channel* references a *Wildfly Channel Manifest* and
Maven repositories). 

## Installation Referencing a Channel Manifest and Maven Repositories

```shell
./prospero install --fpl org.wildfly.core:wildfly-core-galleon-pack:20.0.0.Beta3 \
  --manifest examples/wildfly-core-manifest.yaml \
  --repositories central::https://repo1.maven.org/maven2/ \
  --dir installation-dir
```

Description:
* The `--fpl` option defines a feature pack to be installed.
* The `--manifest` option defines a Wildfly Channel Manifest (specifies Maven artifacts versions).
* The `--repositories` options defines Maven repositories to download Maven artifacts from.

## Upgrade or downgrade to a specific Wildfly channel manifest

You can upgrade or downgrade the Wildfly to a specific version of the channel manifest that is not the latest version. You can also choose to downgrade the channel manifest to a specific previous version.

* List possible updates for the installation:

```shell
./prospero update list-manifest-versions --dir installation-dir
```

* List possible updates for the installation with `--include-downgrades` parameter:

```shell
./prospero update list-manifest-versions --dir installation-dir --include-downgrades
```

Note that the option `--include-downgrades` paramenter will include older manifest versions in the list of available manifests.

* List the components updagraded or downgraded on the manifest:

```shell
./prospero update list --dir installation-dir --manifest-versions wildfly::38.0.1.Final
```

The `--manifest-versions` is the `<CHANNEL_NAME>::<VERSION>` value that will be upgraded or downgraded.

* Prepare or perform the upgrade or downgrade:

```shell

# 1. Prepare command
./prospero update prepare --candidate-dir candidate-dir --dir installation-dir --manifest-versions wildfly::38.0.1.Final

./prospero update apply --candidate-dir candidate-dir --dir installation-dir

# 2. Perform command
./prospero update perform --dir installation-dir --manifest-versions wildfly::38.0.1.Final
```

_Downgrading the manifest version will cause a warning to issue to prevent accidental downgrade. When prompted, "The update will downgrade the following channels: ... Do you want to continue? [y/N]", select `y`._

_Before performing the upgrade operation, the installation manager will print a list of changes to be performed. When prompted, "Do you want to continue? [y/N]", select `y`._

**NOTE: Use the `--yes` flag for non-interactive downgrades. This parameter overrides the system prompts mentioned above.**
