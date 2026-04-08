# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

Multi-module Gradle project (Java 21, Spring Boot 3.5.6). Use the wrapper:

- Build all: `./gradlew build`
- Run chatting module: `./gradlew :chatting:bootRun`
- Test all: `./gradlew test`
- Test single module: `./gradlew :chatting:test`
- Single test class: `./gradlew :chatting:test --tests com.brunosong.system.chatting.SomeTest`

## Architecture

- Root `build.gradle` applies Spring Boot, dependency-management, and Lombok to all subprojects via a `subprojects { }` block. Subproject `build.gradle` files therefore omit plugin declarations and Lombok deps.
- Modules are registered in `settings.gradle` (currently only `chatting`). New modules: create the directory, add `include 'name'`, and add a minimal `build.gradle` with only group/version/repos and module-specific dependencies.
- Group: `com.brunosong`. Chatting module package: `com.brunosong.system.chatting`.
