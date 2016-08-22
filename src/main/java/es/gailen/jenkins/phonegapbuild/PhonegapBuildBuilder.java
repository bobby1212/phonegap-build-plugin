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

    private final String name;
    private final String version;
    private final String pgbuildAppId;
    private final String pgbuildToken;
    private final String androidKeyId;
    private final String androidKeyPassword;
    private final String androidKeystorePassword;
    private final String iosKeyId;
    private final String iosKeyPassword;

    @DataBoundConstructor
    public PhonegapBuildBuilder(String name, String version, String pgbuildAppId, String pgbuildToken, String androidKeyId, String androidKeyPassword, String androidKeystorePassword, String iosKeyId, String iosKeyPassword) {
        this.name = name;
        this.version = version;
        this.pgbuildAppId = pgbuildAppId;
        this.pgbuildToken = pgbuildToken;
        this.androidKeyId = androidKeyId;
        this.androidKeyPassword = androidKeyPassword;
        this.androidKeystorePassword = androidKeystorePassword;
        this.iosKeyId = iosKeyId;
        this.iosKeyPassword = iosKeyPassword;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     */
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

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        EnvVars env = build.getEnvironment(listener);
        PhonegapBuilder builder = new PhonegapBuilder(this.pgbuildToken, this.pgbuildAppId, this.androidKeyId, this.iosKeyId, listener.getLogger());
        builder.setVersion(env.expand(this.getVersion()));
        builder.setAppName(env.expand(this.getName()));
        builder.unlockKeys(this.androidKeyPassword, this.androidKeystorePassword, this.iosKeyPassword);
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
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */
        private boolean useFrench;

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
            if (value.length() == 0)
                return FormValidation.error("Please set a name");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the name too short?");
            return FormValidation.ok();
        }

        public FormValidation doCheckPgbuildAppId(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set PGBuild's job ID");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the name too short?");
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
            //useFrench = formData.getBoolean("useFrench");
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

        /**
         * This method returns true if the global configuration says we should speak French.
         *
         * The method name is bit awkward because global.jelly calls this method to determine
         * the initial state of the checkbox by the naming convention.
         */
        public boolean getUseFrench() {
            return useFrench;
        }
    }
}

