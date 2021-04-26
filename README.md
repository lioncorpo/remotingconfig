Jenkins Remoting layer
====

Jenkins remoting is an executable JAR, 
which implements communication layer in [Jenkins](https://jenkins.io) automation server. 
It's being used for master <=> agent(fka "slave") and master <=> CLI communications.

In general, this library contains the bootstrap code to bridge separate JVMs into a single semi-shared space.
It includes: TCP-based communication protocols, data serialization, Java classloading.

The library is reusable outside Jenkins.



### Documentation

Remoting documentation is under development.
More info will appear soon.
Feel free to contribute.

User documentation:

* [Changelog](CHANGELOG.md) - Remoting release notes
* [Remoting Protocols](docs/protocols.md) - Overview of protocols integrated with Jenkins
* [Remoting Configuration](docs/configuration.md) - Configuring remoting agents
* [Logging](docs/logging.md) - Logging
* [Work Directory](docs/workDir.md) - Remoting work directory (new in Remoting `3.8`)
* [Jenkins Specifics](docs/jenkins-specifics.md) - Notes on using remoting in Jenkins
* [Troubleshooting](docs/troubleshooting.md) - Investigating and solving common remoting issues

Previous versions:

* [Remoting versions](docs/versions.md) - Description of Remoting `2.x` and `3.x` differences
* [Changelog - 2.x](CHANGELOG-2.x.md) - Changelog for the Remoting `2.x` stabilization releases
* [Remoting 3 Compatibility Notes](docs/remoting-3-compatibility.md)

Developer documentation:

* [Contributing](CONTRIBUTING.md)
* [Channel Termination Process](docs/close.md)

### Reporting issues

Remoting library uses the [Jenkins bugtracker](https://issues.jenkins-ci.org).
Issues should be reported there in the <code>JENKINS</code> project with the <code>remoting</code> component.

See [How to report an issue](https://wiki.jenkins-ci.org/display/JENKINS/How+to+report+an+issue) for more details about Jenkins issue reporting.

### More info

* [Remoting Architecture Overview](http://hudson-ci.org/docs/HudsonArch-Remoting.pdf) 
by Winston Prakash, Oracle (the information is outdated)
* [Making your plugin behave in distributed Jenkins](https://wiki.jenkins-ci.org/display/JENKINS/Making+your+plugin+behave+in+distributed+Jenkins)
* [Writing an SCM plugin. Remoting examples](https://wiki.jenkins-ci.org/display/JENKINS/Remoting)
* [Troubleshooting remoting issues](https://wiki.jenkins-ci.org/display/JENKINS/Remoting+issue)
* [Scaling Jenkins to Hundreds of Nodes](https://www.cloudbees.com/jenkins/juc-2015/abstracts/us-west/02-01-1600) 
by Akshay Dayal, Google (remoting optimization, JNLP3)