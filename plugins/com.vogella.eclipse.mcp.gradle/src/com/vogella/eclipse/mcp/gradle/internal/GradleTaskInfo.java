package com.vogella.eclipse.mcp.gradle.internal;

/** A Gradle task as the tools report it; {@code project} is the Gradle path of the project that declares it. */
record GradleTaskInfo(String project, String name, String path, String group, String description, boolean isPublic) {
}
