name := """secman"""
organization := "com.example"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayJava)

scalaVersion := "2.13.14"

libraryDependencies ++= Seq(
  guice,
  jdbc,
  javaJpa,
  evolutions,
  "org.mariadb.jdbc" % "mariadb-java-client" % "3.5.3", // Updated MariaDB driver
  "javax.validation" % "validation-api" % "2.0.1.Final",
  "org.hibernate.orm" % "hibernate-core" % "6.6.1.Final", // Updated Hibernate
  "org.hibernate.validator" % "hibernate-validator" % "6.2.5.Final",
  "jakarta.validation" % "jakarta.validation-api" % "3.0.2",
  "org.glassfish" % "jakarta.el" % "4.0.2",
  "org.apache.poi" % "poi" % "5.3.0", // Updated Apache POI core
  "org.apache.poi" % "poi-ooxml" % "5.3.0", // Updated Apache POI OOXML
  "org.mindrot" % "jbcrypt" % "0.4", // Added jBCrypt for password hashing
  "org.apache.logging.log4j" % "log4j-to-slf4j" % "2.23.1", // Updated log4j bridge
  "org.slf4j" % "slf4j-api" % "2.0.13", // Updated SLF4J API dependency
  "org.playframework" %% "play-json" % "3.0.2", // Updated Play JSON library
  "jakarta.persistence" % "jakarta.persistence-api" % "3.1.0", // Updated JPA API dependency
  "javax.mail" % "javax.mail-api" % "1.6.2", // Email API
  "com.sun.mail" % "javax.mail" % "1.6.2", // Email implementation
  ws, // Play WebSocket client for HTTP requests
  // OAuth/OIDC and JWT dependencies
  "com.auth0" % "java-jwt" % "4.4.0", // JWT library
  "com.nimbusds" % "nimbus-jose-jwt" % "9.40", // Advanced JWT/JWS/JWE library
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.14.3", // JSON processing
  "org.apache.commons" % "commons-lang3" % "3.14.0", // Utilities for string manipulation
  "commons-codec" % "commons-codec" % "1.17.1", // Base64 and other encoding utilities
  // Test dependencies
  "org.mockito" % "mockito-core" % "5.12.0" % Test, // Updated Mockito
  "junit" % "junit" % "4.13.2" % Test,
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test, // Updated ScalaTest
  "com.h2database" % "h2" % "2.2.224" % Test, // Keep compatible H2 version
  "org.hibernate.orm" % "hibernate-core" % "6.6.1.Final" % Test, // Updated Hibernate for tests
  "jakarta.persistence" % "jakarta.persistence-api" % "3.1.0" % Test, // Updated JPA for tests
  "com.typesafe.akka" %% "akka-actor-typed" % "2.8.6" % Test, // Updated Akka
  "com.typesafe.akka" %% "akka-stream" % "2.8.6" % Test // Updated Akka Stream
)

// Disable documentation generation
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false

PlayKeys.playDefaultPort := 9000
