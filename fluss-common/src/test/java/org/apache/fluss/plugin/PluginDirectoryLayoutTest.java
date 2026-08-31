/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.fluss.plugin;

import org.apache.fluss.plugin.jar.plugina.DynamicClassA;
import org.apache.fluss.plugin.jar.plugina.TestServiceA;
import org.apache.fluss.shaded.guava32.com.google.common.collect.Lists;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.fluss.utils.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the plugin directory layout of a deployed cluster, where each plugin lives in its own
 * sub directory of {@code <FLUSS_HOME>/plugins} and the main classpath is {@code <FLUSS_HOME>/lib}.
 *
 * <p>These cover the behaviour users actually depend on when several plugins are installed at once,
 * and pin down why placing a plugin jar on the main classpath in addition to the plugins directory
 * breaks the deployment.
 */
class PluginDirectoryLayoutTest extends PluginTestBase {

    @TempDir private Path tmp;

    /** Several plugins installed side by side must all be discovered and stay isolated. */
    @Test
    void testMultiplePluginsLoadedSimultaneously() throws Exception {
        PluginManager pluginManager =
                createPluginManager(
                        new String[] {TestSpi.class.getName(), OtherTestSpi.class.getName()},
                        "multi");

        List<TestSpi> plugins = Lists.newArrayList(pluginManager.load(TestSpi.class));

        assertThat(plugins).hasSize(2);
        Set<ClassLoader> classLoaders = new HashSet<>();
        for (TestSpi plugin : plugins) {
            assertThat(plugin.testMethod()).isNotNull();
            // every plugin gets its own class loader, none of them is the main classpath
            assertThat(plugin.getClassLoader()).isNotSameAs(PARENT_CLASS_LOADER);
            classLoaders.add(plugin.getClassLoader());
        }
        assertThat(classLoaders).hasSize(2);
    }

    /** A plugin must not see classes bundled inside another plugin's jar. */
    @Test
    void testPluginsDoNotSeeEachOthersClasses() throws Exception {
        PluginManager pluginManager =
                createPluginManager(
                        new String[] {TestSpi.class.getName(), OtherTestSpi.class.getName()},
                        "isolation");

        List<TestSpi> plugins = Lists.newArrayList(pluginManager.load(TestSpi.class));
        assertThat(plugins).hasSize(2);

        // DynamicClassA is bundled in plugin A's jar only
        ClassLoader loaderOfA = classLoaderOf(plugins, TestServiceA.class.getName());
        ClassLoader loaderOfB = otherClassLoader(plugins, loaderOfA);

        assertThat(loaderOfA.loadClass(DynamicClassA.class.getName())).isNotNull();
        assertThatThrownBy(() -> loaderOfB.loadClass(DynamicClassA.class.getName()))
                .isInstanceOf(ClassNotFoundException.class);
    }

    /**
     * The same jar deployed both in the plugins directory and on the main classpath: the main
     * classpath copy wins, because its package is loaded parent-first. The dependencies bundled
     * next to the plugin are then invisible, which is what makes such a deployment fail at runtime.
     */
    @Test
    void testClassOnMainClasspathShadowsPluginCopy() throws Exception {
        // TestServiceA is on the main classpath here, standing in for a plugin jar that was also
        // copied into <FLUSS_HOME>/lib, and org.apache.fluss. is parent-first by default
        PluginManager pluginManager =
                createPluginManager(
                        new String[] {
                            TestSpi.class.getName(),
                            OtherTestSpi.class.getName(),
                            TestServiceA.class.getName()
                        },
                        "shadowed");

        List<TestSpi> plugins = Lists.newArrayList(pluginManager.load(TestSpi.class));
        TestSpi fromPluginA = pluginNamed(plugins, TestServiceA.class.getName());

        // loaded from the main classpath instead of from the plugin jar
        assertThat(fromPluginA).isInstanceOf(TestServiceA.class);
        assertThat(fromPluginA.getClassLoader()).isSameAs(PARENT_CLASS_LOADER);
    }

    /** Without the parent-first pattern the very same layout resolves from the plugin jar. */
    @Test
    void testPluginCopyIsUsedWhenNotOnMainClasspath() throws Exception {
        PluginManager pluginManager =
                createPluginManager(
                        new String[] {TestSpi.class.getName(), OtherTestSpi.class.getName()},
                        "not-shadowed");

        List<TestSpi> plugins = Lists.newArrayList(pluginManager.load(TestSpi.class));
        TestSpi fromPluginA = pluginNamed(plugins, TestServiceA.class.getName());

        // same class name, but a distinct class loaded from the plugin jar
        assertThat(fromPluginA).isNotInstanceOf(TestServiceA.class);
        assertThat(fromPluginA.getClassLoader()).isNotSameAs(PARENT_CLASS_LOADER);
    }

    /**
     * Builds a {@code plugins/} root holding plugin A and plugin B in separate sub directories, the
     * way a Fluss distribution lays them out.
     */
    private PluginManager createPluginManager(String[] parentPatterns, String name)
            throws Exception {
        File pluginRootFolder = new File(tmp.toFile(), name);
        File pluginAFolder = new File(pluginRootFolder, "A");
        File pluginBFolder = new File(pluginRootFolder, "B");
        checkState(pluginAFolder.mkdirs());
        checkState(pluginBFolder.mkdirs());
        Files.copy(locateJarFile(PLUGIN_A).toPath(), Paths.get(pluginAFolder.toString(), PLUGIN_A));
        Files.copy(locateJarFile(PLUGIN_B).toPath(), Paths.get(pluginBFolder.toString(), PLUGIN_B));

        Collection<PluginDescriptor> descriptors =
                new DirectoryBasedPluginFinder(pluginRootFolder.toPath()).findPlugins();
        checkState(descriptors.size() == 2);
        return new DefaultPluginManager(descriptors, PARENT_CLASS_LOADER, parentPatterns);
    }

    private static TestSpi pluginNamed(List<TestSpi> plugins, String className) {
        for (TestSpi plugin : plugins) {
            if (plugin.getClass().getName().equals(className)) {
                return plugin;
            }
        }
        throw new AssertionError("No plugin found with class name " + className);
    }

    private static ClassLoader classLoaderOf(List<TestSpi> plugins, String className) {
        return pluginNamed(plugins, className).getClassLoader();
    }

    private static ClassLoader otherClassLoader(List<TestSpi> plugins, ClassLoader classLoader) {
        for (TestSpi plugin : plugins) {
            if (plugin.getClassLoader() != classLoader) {
                return plugin.getClassLoader();
            }
        }
        throw new AssertionError("Expected a second, distinct plugin class loader");
    }
}
