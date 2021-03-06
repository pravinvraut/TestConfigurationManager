package ch.racic.testing.cm;

import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@Mojo(
        name = "generate",
        requiresProject = true,
        defaultPhase = LifecyclePhase.NONE
)
@Execute(phase = LifecyclePhase.GENERATE_SOURCES)
public class ConfigGeneratorMojo extends AbstractMojo {

    @Component
    protected MavenProject project;

    @Component
    private Settings settings;

    @Parameter(
            required = true,
            readonly = true,
            defaultValue = "${project.build.directory}/generated-sources/config",
            property = "outputDir"
    )
    private File outputDir;

    @Parameter(
            required = true,
            readonly = true,
            defaultValue = "${project.groupId}.gen.config",
            property = "basePackage"
    )
    private String basePackage;

    @Parameter(
            required = true,
            readonly = true,
            defaultValue = "false",
            property = "testSourceOnly"
    )
    private boolean testSourceOnly;

    public void execute() throws MojoExecutionException, MojoFailureException {
        // Get resource directories, there could be more than 1 and on main and test level
        List<Resource> resources = project.getTestResources();
        resources.addAll(project.getResources());
        // getting root resource folders and check if there is a config/global folder to determine if there are any interesting files to parse?
        List<File> configDirs = filterResources(resources);
        // parse global config properties
        // Generate a class G which contains a static inner class for each property file with constants from the property names
        Properties globalProps;
        try {
            getLog().debug("Generate global constant class");
            globalProps = generateG(configDirs);
        } catch (IOException e) {
            throw new MojoExecutionException("Error while generating G class from template", e);
        }
        // find class folders
        List<File> classConfigDirs = filterClassResources(configDirs);
        // Generate a class C which contains a static inner class for each class config with constants from property names
        try {
            getLog().debug("Generate class specific constant class");
            generateC(classConfigDirs, globalProps);
        } catch (IOException e) {
            throw new MojoExecutionException("Error while generating C class from template", e);
        }

        // After all is generated, let's add it to the corresponding source root
        if (testSourceOnly) loadGeneratedTestSources();
        else loadGeneratedSources();

    }

    private void generateC(List<File> classConfigDirs, Properties globalProps) throws IOException, MojoFailureException {
        // Generate a class C which contains a static inner class for each class config with constants from property names
        Map<String, Properties> innerClasses = new HashMap<String, Properties>();
        for (File configDir : classConfigDirs) {
            getLog().debug("Scanning config directory at path " + configDir.getAbsolutePath());
            //Iterate over all config dirs and look for properties
            for (File propFile : configDir.listFiles(ConfigProvider.propertiesFilter)) {
                getLog().debug("Parsing property file " + propFile.getName());
                // create inner class with property file name
                Properties classProps = new Properties();
                try {
                    classProps.load(FileUtils.openInputStream(propFile));
                    // Remove properties which are already contained in teh global file
                    for (String key : classProps.stringPropertyNames()) {
                        if (globalProps.containsKey(key)) {
                            classProps.remove(key);
                            getLog().debug("Found global properties override in class file " + propFile.getName() + " [" + key + "]");
                        }
                    }
                    // detect duplicate properties
                    for (Properties pc : innerClasses.values()) {
                        Sets.SetView<String> intersection = Sets.intersection(classProps.stringPropertyNames(), pc.stringPropertyNames());
                        if (!intersection.isEmpty()) {
                            String throwie = "Found duplicate property definitions, please don't reuse property keys for different purposes";
                            for (String prop : intersection) throwie += " [" + prop + "]";
                            getLog().warn(throwie);
                        }
                    }
                } catch (IOException e) {
                    getLog().error("Could not read properties file from " + propFile.getAbsolutePath(), e);
                    //continue with the next
                    // TODO should we abort here or just log the error and continue?
                }
                if (innerClasses.containsKey(propFile.getName()))
                    throw new MojoFailureException("Duplicate properties file found in resource paths on same layer: "
                            + propFile.getAbsolutePath() + " and " + innerClasses.get(propFile.getName()));
                innerClasses.put(propFile.getName(), classProps);
            }
        }
        // generate class from velocity template ConstantsClass.vm
        generateFileFromTemplate("ConstantsClass.vm", "C", innerClasses);
    }

    private Properties generateG(List<File> configDirs) throws IOException, MojoFailureException {
        // generate a class G which contains a static inner class for each property file with constants from the property names
        Map<String, Properties> innerClasses = new HashMap<String, Properties>();
        Properties ret = new Properties();
        for (File configDir : configDirs) {
            //Iterate over all config dirs and look for properties
            for (File propFile : configDir.listFiles(ConfigProvider.propertiesFilter)) {
                // create inner class with property file name
                Properties classProps = new Properties();
                try {
                    classProps.load(FileUtils.openInputStream(propFile));
                    // Add the properties for returning all the keys
                    ret.putAll(classProps);
                    // detect duplicate properties
                    for (Properties pc : innerClasses.values()) {
                        Sets.SetView<String> intersection = Sets.intersection(classProps.stringPropertyNames(), pc.stringPropertyNames());
                        if (!intersection.isEmpty()) {
                            String throwie = "Found duplicate property definitions, please make keys unique over files on same layer:";
                            for (String prop : intersection) throwie += " [" + prop + "]";
                            throw new MojoFailureException(throwie);
                        }
                    }
                } catch (IOException e) {
                    getLog().error("Could not read properties file from " + propFile.getAbsolutePath(), e);
                    //continue with the next
                    // TODO should we abort here or just log the error and continue?
                }
                // Check if the filename is duplicated in resource paths
                if (innerClasses.containsKey(propFile.getName()))
                    throw new MojoFailureException("Duplicate properties file found in resource paths on same layer: "
                            + propFile.getAbsolutePath() + " and " + innerClasses.get(propFile.getName()));
                innerClasses.put(propFile.getName(), classProps);
            }
        }

        // generate class from velocity template ConstantsClass.vm
        generateFileFromTemplate("ConstantsClass.vm", "G", innerClasses);

        return ret;
    }

    private void generateFileFromTemplate(String templateName, String generatedClassName, Map<String, Properties> innerClasses) throws IOException {
        VelocityContext context = new VelocityContext();
        context.put("generatedClassName", generatedClassName);
        context.put("packageName", basePackage);
        context.put("classList", innerClasses);

        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADER, "file");
        ve.setProperty("file.resource.loader.class", ClasspathResourceLoader.class.getName());
        ve.init();
        Template template = Velocity.getTemplate("ch.racic.testing.cm/" + templateName);

        File outputFile = new File(outputDir, generatedClassName + ".java");
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));

        template.merge(context, writer);
        writer.flush();
        writer.close();
        getLog().debug("Generate java class " + outputFile.getAbsolutePath());
    }

    private List<File> filterClassResources(List<File> configDirs) {
        List<File> classConfigFolders = new ArrayList<File>();
        for (File cDir : configDirs) {
            File classConfig = new File(cDir, ConfigProvider.CONFIG_CLASS_FOLDER);
            if (classConfig.exists() && classConfig.isDirectory()) {
                classConfigFolders.add(classConfig);
            }
        }
        return classConfigFolders;
    }

    private List<File> filterResources(List<Resource> resources) {
        List<File> configFolders = new ArrayList<File>();
        // iterate over the resource folders and see which contain any config folder
        for (Resource res : resources) {
            File root = new File(res.getDirectory());
            File config = new File(root, ConfigProvider.CONFIG_BASE_FOLDER);
            File global = new File(config, ConfigProvider.CONFIG_GLOBAL_BASE_FOLDER);
            if (global.exists() && global.isDirectory()) {
                // Found a global config folder
                configFolders.add(global);
            }
        }
        return configFolders;
    }

    private void loadGeneratedTestSources() {
        if (!settings.isInteractiveMode()) {
            getLog().info(String.format("Adding generated configuration classes from %s to test compile source root",
                    outputDir.getAbsolutePath()));
        }
        project.addTestCompileSourceRoot(outputDir.getAbsolutePath());
    }

    private void loadGeneratedSources() {
        if (!settings.isInteractiveMode()) {
            getLog().info(String.format("Adding generated configuration classes from %s to compile source root",
                    outputDir.getAbsolutePath()));
        }
        project.addCompileSourceRoot(outputDir.getAbsolutePath());
    }
}
