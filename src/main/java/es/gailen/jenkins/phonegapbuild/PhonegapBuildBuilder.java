package es.gailen.jenkins.phonegapbuild;

import hudson.Launcher;
import hudson.Extension;
import hudson.EnvVars;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Based on Jenkins' Sample {@link Builder}.
 *
 * <p>
 * Will connect delegate 'phonegap build' tasks to the {@link PhonegapBuilder}
 * Only responsibilites are fetching parameters from UI and delegating the build.
 * <p>
 *
 */
public class PhonegapBuildBuilder extends Builder {

    private boolean configOverrided = false;
    private String name = null;
    private String version = null;
    private boolean createNewApp = false;
    private String pgbuildAppId;
    private final String pgbuildToken;
    private final String androidKeyId;
    private final String androidKeyPassword;
    private final String androidKeystorePassword;
    private final String iosKeyId;
    private final String iosKeyPassword;

    @DataBoundConstructor
    public PhonegapBuildBuilder(JSONObject overrideConfig, boolean createNewApp, String pgbuildAppId, String pgbuildToken, String androidKeyId, String androidKeyPassword, String androidKeystorePassword, String iosKeyId, String iosKeyPassword) {
        if (overrideConfig != null) {
            this.configOverrided = true;
            this.name = overrideConfig.getString("name");
            this.version = overrideConfig.getString("version");
        }
        this.createNewApp = createNewApp;
        if (this.createNewApp)
            this.pgbuildAppId = null;
        else
            this.pgbuildAppId = pgbuildAppId;

        this.pgbuildToken = pgbuildToken;
        this.androidKeyId = androidKeyId;
        this.androidKeyPassword = androidKeyPassword;
        this.androidKeystorePassword = androidKeystorePassword;
        this.iosKeyId = iosKeyId;
        this.iosKeyPassword = iosKeyPassword;
    }

    public boolean isOverridedConfig() {
        return name != null || version != null;
    }
    public String getName() {
        return name;
    }

    public String getPgbuildAppId() {
        return pgbuildAppId;
    }

    public String getPgbuildToken() {
        return pgbuildToken;
    }

    public String getAndroidKeyId() {
        return androidKeyId;
    }

    public String getAndroidKeyPassword() {
        return androidKeyPassword;
    }

    public String getAndroidKeystorePassword() {
        return androidKeystorePassword;
    }

    public String getIosKeyId() {
        return iosKeyId;
    }

    public String getIosKeyPassword() {
        return iosKeyPassword;
    }

    public String getVersion() {
        return version;
    }

    // Will *never* return true
    public boolean getCreateNewApp() {
        return this.createNewApp;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        EnvVars env = build.getEnvironment(listener);
        if (this.createNewApp) {
            this.pgbuildAppId = PhonegapBuilder.createNewApp(this.pgbuildToken, listener.getLogger());
            this.createNewApp = false;
        }
        PhonegapBuilder builder = new PhonegapBuilder(this.pgbuildToken, this.pgbuildAppId, this.androidKeyId, this.iosKeyId, listener.getLogger());
        builder.setFileBaseName(env.get("JOB_NAME"));
        if (this.configOverrided) {
            builder.setVersion(env.expand(this.getVersion()));
            builder.setAppName(env.expand(this.getName()));
        }
        builder.buildApp(build.getWorkspace().absolutize());

        return true;

    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link PhonegapBuildBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/es/gailen/jenkins/phonegapbuild/PhonegapBuildBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Validators
         */
        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            return FormValidation.ok();
        }

        public FormValidation doCheckPgbuildAppId(@QueryParameter String value, @QueryParameter Boolean createNewApp)
                throws IOException, ServletException {
            if (createNewApp) {
                if (value.length() > 0)
                    return FormValidation.warning("This ID will be lost because you've checked 'Create new app'");
            } else {
                if (value.length() == 0)
                    return FormValidation.error("Please set PGBuild's job ID");
                if (value.length() < 4)
                    return FormValidation.warning("Isn't that ID too short? Should be something like 6453738");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckCreateNewApp(@QueryParameter Boolean value, @QueryParameter String pgbuildAppId) throws IOException, ServletException {
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Ejecutar job de Phonegap Build";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            System.out.println("Configure: "+ formData.toString());
            save();
            return super.configure(req,formData);
        }
    }
}

