reveal-media-webservice
=======================

Exposes the media analysis and retrieval API of the REVEAL project.
The project is made of two main packages:

### it.unimi.di.law : the crawler
- The code is a copy of the [BUbiNG source code][1] 
- The related [paper][2]
- And a [poster][3]
- More information about the crawler configuration [here][4]
- BUbiNG is also published on [maven central][5]. The reason we are not using the maven dependency and just a copy of the code instead, is that it was necessary to modify many classes to fit our needs and this was not possible by just extending . Some of the core code had to be changed, and especially the classes in the parse and store sub-packages.

### gr.iti.mklab.reveal : the REST API. 
The main class which exposes all the functionalities is the ```RevealController```. In the constructor, several objects are initialized, as well as the configuration in the following line: ```Configuration.load(getClass().getResourceAsStream("/remote.properties"));```

Choose one of the following files:
- remote.properties for the iti-310 configuration
- local.properties for the localhost configuration
- docker.properties for the docker configuration

Another important class is the ```RevealAgent```, which controls and initializes the crawler. The configuration file for the crawler can be found in the ```resources``` folder of the project, the name is ```reveal.properties```. For more details about the configuration, you should have a look at the BUbiNG documentation. 

Important note:  The whole text package is obsolete. Now we are using iit.demokritos maven dependency instead. I'm just leaving the code as a reference but it can as well be deleted. 

The crawler has the possibility of also crawling social media by using the [simmo-stream-manager web service][6]. This can be configured in the properties file.

[1]:  http://law.di.unimi.it/software.php#bubing
[2]:  http://www.quantware.ups-tlse.fr/FETNADINE/papers/P4.8.pdf
[3]:  http://wwwconference.org/proceedings/www2014/companion/p227.pdf
[4]:  http://law.di.unimi.it/software/bubing-docs/overview-summary.html
[5]:  https://search.maven.org/#artifactdetails|it.unimi.di.law|bubing|0.9.11|jar
[6]:  https://github.com/MKLab-ITI/simmo-stream-manager 