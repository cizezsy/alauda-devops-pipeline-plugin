package com.alauda.jenkins.plugins

import com.cloudbees.groovy.cps.NonCPS
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.alauda.jenkins.plugins.pipeline.AcpAction
import com.alauda.jenkins.plugins.pipeline.OcContextInit

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hudson.AbortException
import hudson.FilePath
import hudson.Util

import java.util.logging.Level
import java.util.logging.Logger

import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.CpsThread;
import org.jenkinsci.plugins.workflow.cps.CpsScript

class AlaudaDevopsDSL implements Serializable {

    static final Logger LOGGER = Logger.getLogger(AlaudaDevopsDSL.class.getName());
    private int logLevel = 0; // Modified by calls to alaudaDevops.logLevel
    private CpsScript script
    private transient Devops.DescriptorImpl config = new Devops.DescriptorImpl();
    // Load the global config for Devops DSL
    private HashMap<String, Capabilities> nodeCapabilities = new HashMap<String, Capabilities>();

    /**
     * Prints a log message to the Jenkins log, bypassing the echo step.
     * @param s The message to log
     */
    @NonCPS
    public static void logToTaskListener(String s) {
        CpsThread thread = CpsThread.current();
        CpsFlowExecution execution = thread.getExecution();

        try {
            execution.getOwner().getListener().getLogger().println(s);
        } catch (IOException e) {
            LOGGER.log(Level.INFO, "logToTaskListener", e);
        }
    }

    public AlaudaDevopsDSL(CpsScript script) {
        this.script = script
    }

    public synchronized Capabilities getCapabilities() {
        String key = script.env.NODE_NAME
        Capabilities caps = nodeCapabilities.get(key)
        if (caps != null) {
            return caps
        } else {
            caps = new Capabilities()
            ArrayList<String> g = new ArrayList<String>();
            g.add("get");
            AcpAction.AcpActionResult versionCheck = (AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("help", g, null, null));
            LOGGER.log(Level.FINE, "getCapabilities return from kubectl help get " + versionCheck.out);
            caps.ignoreNotFound = versionCheck.out.contains("--ignore-not-found")
            nodeCapabilities.put(key, caps)
            LOGGER.log(Level.FINE, "getCapabilities nodeCapabilities: " + nodeCapabilities);
            return caps
        }
    }

    private Context currentContext = null;

    private static final Map<String, String> abbreviations = [
            "svc": "service",
            "p"  : "pod",
            "po" : "pod",
            "bc" : "buildconfig",
            "is" : "imagestream",
            "rc" : "replicationcontroller",
            "dc" : "deploymentconfig"]

    enum ContextId implements Serializable {
        WITH_CLUSTER("alaudaDevops.withCluster"), WITH_PROJECT("alaudaDevops.withProject"), WITH_CREDENTIALS("alaudaDevops.withCredentials")
        private final String name;

        ContextId(String name) {
            this.@name = name;
        }

        public String toString() {
            return name;
        }
    }

    private class Context implements Serializable {

        protected final Context parent;
        private final OcContextInit.Execution exec;

        private String credentialsId;
        private String serverUrl;
        private String serverCertificateAuthorityPath;
        private Boolean skipTLSVerify;
        private String project;
        private ContextId id;

        private List<FilePath> destroyOnReturn = new ArrayList<FilePath>();

        protected Context(Context parent, ContextId id) {
            this.@parent = parent;
            this.@id = id;
            this.@exec = script._OcContextInit();

        }

        public <V> V run(Closure<V> body) {
            if (destroyOnReturn == null) {
                throw new IllegalStateException(this.getClass() + " has already been performed once and cannot be used again");
            }
            Context lastContext = currentContext;
            currentContext = this;
            try {
                return body()
            } finally {
                currentContext = lastContext;
                destroyOnReturn.each { fp -> fp.delete() }
                destroyOnReturn = null;
            }
        }

        public ContextId getContextId() {
            return this.@id;
        }

        public String getToken() {
            if (this.@credentialsId != null) {
                DevopsTokenCredentials tokenSecret = CredentialsProvider.findCredentialById(credentialsId, DevopsTokenCredentials.class, script.$build(), Collections.emptyList());
                if (tokenSecret != null) {
                    return tokenSecret.getToken();
                }
                // Otherwise, assume that this is a literal/direct token name
                return this.@credentialsId;
            }

            // avoid asking outer contexts for credentials with
            // withCluster("named") { withCluster ("https://...") { ... } }
            if (this.@serverUrl == null && parent != null) {
                return parent.getToken();
            }

            return script.readFile("/var/run/secrets/kubernetes.io/serviceaccount/token");
        }

        public void setCredentialsId(String credentialsId) {
            this.@credentialsId = Util.fixEmptyAndTrim(credentialsId);
        }

        public String getProject() {
            if (this.@project != null) {
                return this.@project;
            }
            if (parent != null) {
                return parent.getProject();
            }

            return script.readFile("/var/run/secrets/kubernetes.io/serviceaccount/namespace");
        }

        public void setProject(String project) {
            this.@project = Util.fixEmptyAndTrim(project);
        }

        public String getServerCertificateAuthorityPath() {
            if (this.@serverCertificateAuthorityPath != null) {
                return this.@serverCertificateAuthorityPath;
            }
            if (parent != null) {
                return parent.getServerCertificateAuthorityPath();
            }

            // Assume we are running in an Devops pod with a service account mounted
            return "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
        }

        public void setServerCertificateAuthorityContent(String serverCertificateAuthorityContent) {
            serverCertificateAuthorityContent = Util.fixEmptyAndTrim(serverCertificateAuthorityContent);
            if (serverCertificateAuthorityContent != null) {
                /**
                 * The certificate authority content must be written to the agent's file
                 * system. It would be nice if we could set the name in an environment variable
                 * instead.
                 */
                FilePath ca = exec.getWorkspaceFilePath().createTextTempFile("serverca", ".crt", serverCertificateAuthorityContent, false);
                destroyOnReturn.add(ca);
                this.@serverCertificateAuthorityPath = ca.getRemote();
            }
        }

        public String getServerUrl() {
            if (this.@serverUrl != null) {
                return this.@serverUrl;
            }
            if (parent != null) {
                return parent.getServerUrl();
            }
            return ClusterConfig.getHostClusterApiServerUrl();
        }

        public void setServerUrl(String serverUrl, boolean skipTLSVerify) {
            this.@serverUrl = Util.fixEmptyAndTrim(serverUrl);
            this.@skipTLSVerify = skipTLSVerify;
        }

        public boolean isSkipTLSVerify() {
            if (this.@skipTLSVerify != null) {
                return this.@skipTLSVerify;
            }
            if (parent != null) {
                return parent.isSkipTLSVerify();
            }
            return false;
        }
    }

    /**
     * Returns true if the test context identifier is found within the context
     */
    private boolean contextContains(Context context, ContextId test) {
        while (context != null) {
            if (context.getContextId() == test) {
                return true;
            }
            context = context.parent;
        }
        return false;
    }

    @NonCPS
    private void dieIfWithin(ContextId me, Context context, ContextId... forbidden) throws AbortException {
        for (ContextId forbid : forbidden) {
            if (contextContains(context, forbid)) {
                throw new AbortException(me.toString() + " cannot be used within a " + forbid.toString() + " closure body");
            }
        }
    }

    @NonCPS
    // for checks in private methods where the public method used, to included in the exception message, is unclear
    // or embedded
    private void dieIfWithout(Context context, ContextId required, String verb) throws AbortException {
        if (!contextContains(context, required)) {
            verb == "" && (verb = "raw")
            throw new AbortException("You have illegally attempted a " + verb + " command when not inside a " + required.toString() + " closure body")
        }
    }

    @NonCPS
    private void dieIfNotWithin(ContextId me, Context context, ContextId required) throws AbortException {
        if (contextContains(context, required)) {
            throw new AbortException(me.toString() + " can only be used within a " + required.toString() + " closure body");
        }
    }

    public void failUnless(b) {
        b = (new Boolean(b)).booleanValue();
        if (!b) {
            // error is a Jenkins workflow-basic-step
            error("Assertion failed")
        }
    }

    public String project() {
        if (currentContext != null)
            return currentContext.getProject();
        else
            return null;
    }

    public String cluster() {
        if (currentContext != null)
            return currentContext.getServerUrl();
        else
            return null;
    }

    public boolean skipTLSVerify() {
        if (currentContext != null)
            return currentContext.isSkipTLSVerify();
        else
            return false;
    }

    /**
     * @param name The name can be a literal URL for the clusterName or,
     *          preferably, a Jenkins specific
     *          name of a clusterName configured in the global Devops configuration. The name can also
     *          be blank, which means we will default using the default clusterName hostname within a Pod.
     * @param credentialId A literal OAuth token name OR the Jenkins specific identifier of a credential
     *          defined in the Jenkins credentials store.
     */
    public <V> V withCluster(Object oname = null, Object ocredentialId = null, Closure<V> body) {
        String name = toSingleString(oname);
        String credentialId = toSingleString(ocredentialId);

        node {

            // Note that withCluster creates a new Context with null parent. This means that it does not allow
            // operations search outside of its context for more broadly scoped information (i.e.
            // in the DSL: withCredentials(y){ withCluster(...){ x } }, the operation x will not see the credential y.
            // Therefore, to enforce DSL clarity, forbid withCluster from being wrapped.
            dieIfWithin(ContextId.WITH_CLUSTER, currentContext, ContextId.WITH_PROJECT, ContextId.WITH_CREDENTIALS)

            Context context = new Context(null, ContextId.WITH_CLUSTER);

            // Determine if name is a URL or a clusterName name. It is treated as a URL if it is *not* found
            // as a clusterName configuration name.
            ClusterConfig cc = null;

            if (name != null) {
                cc = config.getClusterConfig(name);
            } else {
                // See if a clusterName named "default" has been defined.
                cc = config.getClusterConfig("default");
            }

            if (cc == null) {
                // Presumably, a URL has been specified. If "insecure://..." is a prefix, https is used, but TLS
                // verification is skipped.
                if (name != null) {
                    boolean skipTLS = false;
                    if (name.startsWith("insecure://")) {
                        skipTLS = true;
                        name = "https://" + name.substring("insecure://".length());
                    }
                    context.setServerUrl(name, skipTLS);
                }
            } else {
                context.setServerCertificateAuthorityContent(cc.getServerCertificateAuthority());
                context.setCredentialsId(cc.credentialsId);
                context.setProject(cc.defaultProject);
                context.setServerUrl(cc.getServerUrl(), cc.isSkipTlsVerify());
            }

            if (credentialId != null) {
                context.setCredentialsId(credentialId);
            }

            context.run {
                body()
            }
        }

    }

    public <V> V withProject(Object oprojectName = null, Closure<V> body) {
        String projectName = toSingleString(oprojectName);
        dieIfNotWithin(ContextId.WITH_PROJECT, currentContext, ContextId.WITH_CLUSTER)
        Context context = new Context(currentContext, ContextId.WITH_PROJECT);
        context.setProject(projectName);
        return context.run {
            body()
        }
    }

    public <V> V withCredentials(Object ocredentialId = null, Closure<V> body) {
        String credentialId = toSingleString(ocredentialId);
        dieIfNotWithin(ContextId.WITH_CREDENTIALS, currentContext, ContextId.WITH_CLUSTER)
        Context context = new Context(currentContext, ContextId.WITH_CREDENTIALS);
        context.setCredentialsId(credentialId);
        return context.run {
            body()
        }
    }

    /**
     * Inject SonarQube environment to context.
     * <br>
     * usage example:
     *
     * <pre>
     * withBindingInProjectSonarEnv("test-namespace", "test-code-quality-binding") {
     *     sh "echo \"SonarQube Server URL is $SONAR_SERVER_URL\""
     *     sh "echo \"SonarQube Server token is $SONAR_TOKEN\""
     * }
     * </pre>
     *
     * @param oproject namespace of code quality binding
     * @param obindingName name of code quality binding
     */
    public <V> void withBindInProjectSonarEnv(Object oproject, Object obindingName, Closure<V> body) {
        String project = toSingleString(oproject)
        String bindingName = toSingleString(obindingName)

        def binding
        def secretName
        def secretNamespace
        def apiUrl
        def token

        withProject(project) {
            binding = selector("codequalitybinding.devops.alauda.io", bindingName).object()
            secretName = binding.spec.secret.name
            secretNamespace = binding.spec.secret.namespace

            def toolName = binding.spec.codeQualityTool.name
            def tool = selector("codequalitytool.devops.alauda.io", toolName).object()
            apiUrl = tool.spec.http.host
        }

        withProject(secretNamespace) {
            token = selector("secret", secretName).object().data.password
            // use shebang line to avoid the decoded token to be printed to the console log
            token = script.base64Decode(token)
        }

        script.setProperty("SONAR_SERVER_URL", apiUrl)
        script.setProperty("SONAR_TOKEN", token)

        body()
    }

    // Will eventually be deprecated in favor of withCredentials
    public <V> V doAs(Object ocredentialId = null, Closure<V> body) {
        script.print("WARNING: doAs() is deprecated, use withCredentials() instead")
        return withCredentials(ocredentialId, body)
    }

    public void logLevel(int v) {
        this.@logLevel = v;
    }

    // All lowercase synonym for users familiar with --loglevel on CLI.
    public void loglevel(int v) {
        logLevel(v);
    }

    public void verbose(boolean v = true) {
        logLevel(v ? 8 : 0)
    }

    // Generate the args which will be used by kubectl
    private Map buildCommonArgs(Object overb, List verbArgs, Object[] ouserArgsArray, Object... ooverrideArgs) {
        return buildCommonArgs(true, overb, verbArgs, ouserArgsArray, ooverrideArgs)
    }

    private Map buildCommonArgs(boolean getProject, Object overb, List verbArgs, Object[] ouserArgsArray, Object... ooverrideArgs) {
        dieIfWithout(currentContext, ContextId.WITH_CLUSTER, overb);
        String verb = toSingleString(overb);
        String[] userArgsArray = toStringArray(ouserArgsArray);
        String[] overrideArgs = toStringArray(ooverrideArgs);

        List optionsBase = [];

        // Override args should come after all user arguments. This allows us to ensure
        // certain behaviors (e.g. if we need to send -o=name).
        if (overrideArgs != null) {
            optionsBase.addAll(overrideArgs);
        }

        ArrayList<String> userArgsList = (userArgsArray == null) ? new ArrayList<String>() : Arrays.asList(userArgsArray);

        // These arguments will be mapped, by name, to the constructor parameters of AcpAction
        Map args = [
                server       : currentContext.getServerUrl(),
                project      : (getProject ? currentContext.getProject() : null),
                skipTLSVerify: currentContext.isSkipTLSVerify(),
                caPath       : currentContext.getServerCertificateAuthorityPath(),
                verb         : verb,
                verbArgs     : verbArgs,
                userArgs     : userArgsList,
                options      : optionsBase,
                token        : currentContext.getToken(),
                logLevel     : logLevel
        ]
        return args;
    }

    /**
     * Splits oc verb -o=name output into a list of qualified object names.
     */
    public static ArrayList<String> splitNames(String out) {
        String[] names = (out == null ? new String[0] : out.trim().split("\n"));
        List<String> results = new ArrayList<String>();

        for (int i = 0; i < names.length; i++) {
            String name = names[i].trim();
            if (!name.isEmpty()) {
                results.add(name);
            }
        }

        return results;
    }

    @NonCPS
    public HashMap serializableMap(String json) {
        JsonSlurper js = new JsonSlurper();
        Map m = js.parseText(json);

        /**
         * The Map produced by JsonSlurper is not serializable. If we return it to the user's DSL script and they store it in a global variable,
         * the Jenkins CPS engine will attempt to serialize it before the next AsynchronousStepExecution. This leads to a
         * exceptions like: java.io.NotSerializableException: groovy.json.internal.LazyMap
         * To avoid this, everything we return to the user must be serializable -- transform the JsonSlurper into a serializable map.
         * NonCPS methods may not call non-NonCPS methods and arguments/returns must be serializable.
         * http://stackoverflow.com/questions/37864542/jenkins-pipeline-notserializableexception-groovy-json-internal-lazymap
         */

        HashMap master = new HashMap(m);
        Stack<HashMap> s = new Stack<HashMap>();
        s.push(master);
        while (s.size() > 0) {
            HashMap target = s.pop();
            for (Map.Entry e : target.entrySet()) {
                if (e.getValue() instanceof Map) {
                    HashMap he = new HashMap((Map) e.getValue());
                    e.setValue(he);
                    s.push(he);
                }
                if (e.getValue() instanceof List) {
                    List l = (List) e.getValue();
                    for (int i = 0; i < l.size(); i++) {
                        Object o = l.get(i);
                        if (o instanceof Map) {
                            HashMap he = new HashMap((Map) o);
                            l.set(i, he);
                            s.push(he);
                        }
                    }
                }
            }
        }
        return master;
    }

    /**
     * @param obj An Devops object modeled as a Map
     * @return A Java List containing Devops objects. If the parameter models an Devops List,
     *          the object will be "unwrapped" and the resulting Java List will contain an entry for each item
     *          in the Devops List obj. If the obj is not a list, the return name will be a list with
     *          the obj as its only entry.
     */
    @NonCPS
    public ArrayList<HashMap> unwrapAlaudaDevopsList(HashMap obj) {
        ArrayList<HashMap> r = new ArrayList<HashMap>();

        if (obj.kind != "List") {
            r.add(obj);
        } else {
            if (obj.items == null) {
                obj.items = new ArrayList();
            } else {
                r.addAll(obj.items);
            }
        }
        return r;
    }

    /**
     * @param obj An Devops object modeled as a Map or multiple Devops objects as List<Map>
     * @return If the parameter is a List, it will be combined into a single Devops List model. Otherwise,
     *          the parameter will be returned unchanged.
     */
    @NonCPS
    public Object toSingleObject(Object obj) {
        if (!(obj instanceof List)) {
            return obj;
        }
        // Model an Devops List
        HashMap m = new HashMap();
        m.kind = "List";
        m.apiVersion = "v1"
        m.items = obj;
        return m;
    }

    /**
     * The select operation can be executed multiple ways:
     *      selector()   // Selects all
     *      selector("pod")  // Selects all of a name
     *      selector("dc", "jenkins")   // selects a particular instance dc/jenkins
     *      selector("dc/jenkins")   // selects a particular instance dc/jenkins
     *      selector(["dc/jenkins", "build/ruby1"])   // selects a particular list of resources
     *      selector("dc", [ alabel: 'avalue' ]) // Selects using label values
     * When labels are used, the qualifier will be a map. In other cases, expect a String or null.
     */
    public AlaudaDevopsResourceSelector selector(Object kind = null, Object qualifier = null) {
//        def p = devOpes.selector('deploy', deploymentName).object()
//        p.metadata.labels['newlabel']='newvalue'
//        p.spec.template.spec.containers[0]['image'] = imageUrl
//        devOpes.apply(p)

        AlaudaDevopsResourceSelector selector = new AlaudaDevopsResourceSelector("selector", kind, qualifier);
        return selector;
    }

    private AlaudaDevopsResourceSelector objectDefAction(String verb, Object obj, Object[] userArgs) {
        dieIfWithout(currentContext, ContextId.WITH_CLUSTER, verb);
        // if the obj is broken down to a parseable set of maps (typically as the result of an
        // alaudaDevops.process call, we look to see if the namespace is specified for any of them
        // or if they are not consistently the same namespace:
        // no namespaces set: we make 1 invocation of the verb where we will use the current project stored in
        // the context if it exists (traditional behavior)
        // consistent namespace set: we make 1 invocation of the verb where we don't use the current project stored in
        // the context and let oc and the obj json sort out where objects go
        // inconsistent namespaces: we make an invocation of the verb for each object where will will not override the 
        // project if it is set but use the current project if it is not set
        String namespace = null;
        boolean found = false;
        boolean consistent = true;
        ArrayList objList = null;
        if (obj instanceof List) {
            objList = new ArrayList((List) obj);
            for (Object obj2 : objList) {
                if (obj2 instanceof Map) {
                    HashMap obj2Map = new HashMap((Map) obj2);
                    HashMap obj2MapMetadata = obj2Map.get("metadata");
                    if (obj2MapMetadata != null) {
                        Object ns = obj2MapMetadata.get("namespace");
                        // first time through the list, no 
                        // consistency check
                        if (!found) {
                            found = true;
                            namespace = ns != null ? ns.toString() : null;
                        } else {
                            if (namespace == null && ns == null)
                                continue;
                            if (namespace != null && namespace.equals(ns))
                                continue;
                            consistent = false;
                            break;
                        }
                    }
                }
            }
        }

        Result r = new Result(verb);
        HashMap<String, String> projectNames = null;
        if (consistent) {
            if (namespace == null || namespace.trim().length() == 0)
                namespace = currentContext.getProject();
            r = innerObjectDefAction(verb, obj, userArgs, namespace, r);
        } else {
            projectNames = new HashMap<String, String>();
            for (int i = 0; i < objList.size(); i++) {
                Object obj2 = objList.get(i);
                if (obj2 instanceof Map) {
                    HashMap obj2Map = new HashMap((Map) obj2);
                    HashMap obj2MapMetadata = obj2Map.get("metadata");
                    if (obj2MapMetadata != null) {
                        String ns = (String) obj2MapMetadata.get("namespace");
                        if (ns == null || ns.trim().length() == 0)
                            ns = currentContext.getProject();
                        String kind = obj2Map.get("kind");
                        String name = obj2MapMetadata.get("name");
                        r = innerObjectDefAction(verb, obj2, userArgs, ns, r);
                        // add index in case same kind/name in diff projects
                        projectNames.put(kind.toLowerCase().trim() + "/" + name + i, ns);
                    }
                }
            }
        }
        r.failIf(verb + " returned an error");
        AlaudaDevopsResourceSelector selector = null;
        if (projectNames != null) {
            selector = new AlaudaDevopsResourceSelector(r, AlaudaDevopsDSL.splitNames(r.out), projectNames);
        } else {
            selector = new AlaudaDevopsResourceSelector(r, AlaudaDevopsDSL.splitNames(r.out));
        }
        return selector;
    }

    private Result innerObjectDefAction(String verb, Object obj, Object[] userArgs, String project, Result r) {
        obj = toSingleObject(obj); // Combine a list of objects into a single Devops List model, if necessary.

        if (obj instanceof Map) {
            obj = JsonOutput.toJson(obj);
        }

        String s = obj.toString();
        boolean markup = s.contains("{") || s.contains(":"); // does this look like json or yaml?
        boolean httpref = s.toLowerCase().startsWith("http") && verb.equalsIgnoreCase("create");
        // a create from a http or https raw.githubuser path

        if (httpref) {
            Map stepArgs = buildCommonArgs(verb, ["-f", s], userArgs, "-o=name");
            if (project != null) {
                stepArgs["project"] = project;
            }
            r.actions.add((AcpAction.AcpActionResult) script._AcpAction(stepArgs));
        } else if (markup) {
            FilePath f = currentContext.exec.getWorkspaceFilePath().createTextTempFile(verb, ".markup", s, false);
            try {
                Map stepArgs = buildCommonArgs(verb, ["-f", f.getRemote()], userArgs, "-o=name");
                stepArgs["reference"] = ["${f.getRemote()}": s];
                // Store the markup content for reference in the result
                if (project != null) {
                    stepArgs["project"] = project;
                }
                r.actions.add((AcpAction.AcpActionResult) script._AcpAction(stepArgs));
            } finally {
                f.delete();
            }
        } else {
            // looks like a subVerb was passed in (e.g. alaudaDevops.create("serviceaccount", "jenkins"))
            Map stepArgs = buildCommonArgs(verb, [s], userArgs, "-o=name");
            if (project != null) {
                stepArgs["project"] = project;
            }
            r.actions.add((AcpAction.AcpActionResult) script._AcpAction(stepArgs));
        }

        return r;
    }

    public AlaudaDevopsResourceSelector create(Object obj, Object... args) {
        return objectDefAction("create", obj, args);
    }

    public AlaudaDevopsResourceSelector replace(Object obj, Object... args) {
        return objectDefAction("replace", obj, args);
    }

    public AlaudaDevopsResourceSelector apply(Object obj, Object... args) {
        return objectDefAction("apply", obj, args);
    }

    public ArrayList<HashMap> process(Object obj, Object... oargs) throws AbortException {
        String[] args = toStringArray(oargs);

        if (obj instanceof Map) {
            if (obj.kind != "Template") {
                throw new AbortException("Expected Template object, but received: " + obj.toString());
            }
            // https://github.com/alaudaDevops/origin/issues/12277
            Map template = new HashMap((Map) obj);
            template.metadata.remove('namespace');
            template.metadata.remove('selfLink');
            obj = JsonOutput.toJson(template);
        }

        String s = obj.toString();
        boolean markup = s.contains("{") || s.contains(":")
        boolean httpref = s.toLowerCase().startsWith("http")
        Result r = new Result("process")

        if (httpref) {
            r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("process", ["-f", s], args, "-o=json")));
            r.failIf("process returned an error");
        } else if (markup) { // does this look like json or yaml?
            FilePath f = currentContext.exec.getWorkspaceFilePath().createTextTempFile("process", ".markup", s, false);
            try {
                r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("process", ["-f", f.getRemote()], args, "-o=json")));
                r.failIf("process returned an error");
            } finally {
                f.delete();
            }
        } else {
            // Otherwise, the obj parameter is assumed to be a template name
            r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("process", [s], args, "-o=json")));
            r.failIf("process returned an error");
        }
        // Output should be JSON; unmarshall into a map and transform into a list of objects.
        return unwrapAlaudaDevopsList(serializableMap(r.out));
    }

    public Result patch(Object obj, Object opatch, Object... oargs) throws AbortException {
        String patch = opatch.toString();
        String[] args = toStringArray(oargs);

        if (obj instanceof Map) {
            obj = JsonOutput.toJson(obj);
        }

        String s = obj.toString();
        boolean markup = s.contains("{") || s.contains(":")
        boolean httpref = s.toLowerCase().startsWith("http")
        Result r = new Result("patch")

        if (httpref) {
            r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("patch", ["-f", s, "-p", patch], args)));
            r.failIf("patch returned an error");
        } else if (markup) { // does this look like json or yaml?
            FilePath f = currentContext.exec.getWorkspaceFilePath().createTextTempFile("patch", ".markup", s, false);
            try {
                r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("patch", ["-f", f.getRemote(), "-p", patch], args)));
                r.failIf("patch returned an error");
            } finally {
                f.delete();
            }
        } else {
            // Otherwise, the obj parameter is assumed to be a template name
            r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("patch", [s, "-p", patch], args)));
            r.failIf("patch returned an error");
        }
        return r;
    }

    public Result newProject(Object oname, Object... oargs) {
        String name = toSingleString(oname);
        String[] args = toStringArray(oargs);
        Result r = new Result("newProject");
        r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs(false, "new-project", [name], args, "--skip-config-write")));
        r.failIf("new-project returned an error");
        return r;
    }

    /**
     * API calls with String parameters can receive normal Java strings
     * or gstrings. In the DSl/groovy, gstrings are defined by using double quotes and
     * include some interpolation. Methods within the API should
     * accept either. To this end, accept any type of object and turn
     * it into a string.
     */
    @NonCPS
    private static String toSingleString(Object o) {
        if (o == null) {
            return null;
        }
        return o.toString(); // convert from gstring if necessary
    }

    /**
     * See details in toSingleString for rationale.
     */
    @NonCPS
    private static String[] toStringArray(Object[] args) {
        if (args == null) {
            return new String[0];
        }
        // Unpack a Groovy list as if it were an Array
        // Enables alaudaDevops.run([ 'x', 'y' ])
        if (args.length == 1 && args[0] instanceof List) {
            args = ((List) args[0]).toArray();
        }
        String[] o = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            o[i] = args[i].toString();
        }
        return o;
    }

    /**
     * See details in toSingleString for rationale.
     */
    @NonCPS
    private static ArrayList<String> toStringList(List<Object> objects) {
        ArrayList l = new ArrayList<String>();
        if (objects != null) {
            for (int i = 0; i < objects.size(); i++) {
                l.add(objects.get(i).toString());
            }
        }
        return l;
    }


    public Result raw(Object... oargs) {
        String[] args = toStringArray(oargs);
        Result r = new Result("raw");
        r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("", null, args)));
        r.failIf("raw command " + args + " returned an error");
        return r;
    }

    public Result delete(Object obj, Object... args) {
        AlaudaDevopsResourceSelector deleteSelector = objectDefAction("delete", obj, args);
        //NOTE: more groovy suckage ... I could not pass a AlaudaDevopsResourceSelector, even
        // though it extends Result, into the Result(Result) contructor
        return new Result(deleteSelector.highLevelOperation, deleteSelector.actions);
    }

    public Result set(Object... oargs) {
        String[] args = toStringArray(oargs);
        Result r = new Result("set");
        r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("set", null, args)));
        r.failIf("set returned an error");
        return r;
    }

    private AlaudaDevopsResourceSelector newAppAction(Object[] oargs) {
        String[] args = toStringArray(oargs);
        // so we first run with -o=json so we can examine the template processing results around
        // namespaces; but this of course does not actually create the objects
        // 
        // so we re-run with create on the returned json to effect the actual creation
        Result r = new Result("newApp");
        r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("new-app", null, args, "-o=json")));
        r.failIf("new-app" + " returned an error");
        ArrayList<HashMap> result = unwrapAlaudaDevopsList(serializableMap(r.out));
        return objectDefAction("create", result, new Object[0]);
    }

    private AlaudaDevopsResourceSelector newObjectsAction(String operation, String verb, Object[] oargs) {
        String[] args = toStringArray(oargs);
        Result r = new Result(operation);
        r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs(verb, null, args, "-o=name")));
        r.failIf(verb + " returned an error");
        AlaudaDevopsResourceSelector selector = new AlaudaDevopsResourceSelector(r, AlaudaDevopsDSL.splitNames(r.out));
        return selector;
    }

    public AlaudaDevopsResourceSelector newBuild(Object... args) {
        return newObjectsAction("newBuild", "new-build", args);
    }

    public AlaudaDevopsResourceSelector newApp(Object... args) {
        return newAppAction(args);
    }

    public AlaudaDevopsResourceSelector startBuild(Object... args) {
        return newObjectsAction("startBuild", "start-build", args);
    }

    private Result simplePassthrough(String verb, Object[] oargs) {
        String[] args = toStringArray(oargs);
        Result r = new Result(verb);
        r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs(verb, null, args, null)));
        r.failIf(verb + " returned an error");
        return r;
    }

    public Result exec(Object... args) { return simplePassthrough("exec", args); }

    public Result rsh(Object... args) { return simplePassthrough("rsh", args); }

    public Result rsync(Object... args) { return simplePassthrough("rsync", args); }

    public Result idle(Object... args) { return simplePassthrough("idle", args); }

    public Result _import(Object... args) { return simplePassthrough("import", args); }

    public Result policy(Object... args) { return simplePassthrough("policy", args); }

    public Result run(Object... args) { return simplePassthrough("run", args); }

    public Result secrets(Object... args) { return simplePassthrough("secrets", args); }

    public Result tag(Object... args) { return simplePassthrough("tag", args); }

    public static class Result implements Serializable {

        public final ArrayList<AcpAction.AcpActionResult> actions = new ArrayList<AcpAction.AcpActionResult>();
        public final String highLevelOperation;

        public Result(String highLevelOperation) {
            this.highLevelOperation = highLevelOperation;
        }

        public Result(String highLevelOperation, ArrayList<AcpAction.AcpActionResult> actions) {
            this.highLevelOperation = highLevelOperation;
            this.actions = actions;
        }

        public Result(Result src) {
            this(src.highLevelOperation);
            this.actions = src.actions;
        }

        @NonCPS
        private void addLine(StringBuilder sb, int indent, String line) {
            sb.append("\t" * indent); // multiply string operation repeats string
            sb.append(line);
            sb.append("\n");
        }

        protected failIf(String failMessage) throws AbortException {
            if (getStatus() != 0) {
                StringBuffer sb = new StringBuffer(failMessage + ";\n");
                for (AcpAction.AcpActionResult actionResult : actions) {
                    if (actionResult.isFailed()) {
                        sb.append(actionResult.toString());
                        sb.append("\n");
                    }
                }
                throw new AbortException(sb.toString());
            }
        }

        @NonCPS
        public String getOperation() {
            return this.@highLevelOperation;
        }

        @NonCPS
        public String toString() {
            HashMap m = new HashMap();
            m.put("operation", highLevelOperation);
            m.put("status", getStatus());
            ArrayList actionList = new ArrayList();
            m.put("actions", actionList);
            for (AcpAction.AcpActionResult e : actions) {
                actionList.add(e.toMap());
            }
            String json = JsonOutput.prettyPrint(JsonOutput.toJson(m));
            return json;
        }

        @NonCPS
        public String getOut() {
            StringBuilder sb = new StringBuilder();
            for (AcpAction.AcpActionResult o : actions) {
                String s = o.out
                if (s == null) {
                    continue;
                }
                sb.append(s);
                if (!s.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }

        @NonCPS
        public String getErr() {
            StringBuilder sb = new StringBuilder();
            for (AcpAction.AcpActionResult o : actions) {
                String s = o.err
                if (s == null) {
                    continue;
                }
                sb.append(s);
                if (!s.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }

        @NonCPS
        public int getStatus() {
            int status = 0;
            for (AcpAction.AcpActionResult o : actions) {
                status |= o.status
            }
            return status;
        }

    }

    public class Capabilities implements Serializable {
        private boolean ignoreNotFound;

        public Capabilities() {
        }

        public boolean hasIgnoredNotFound() {
            return ignoreNotFound;
        }

    }

    public class AlaudaDevopsResourceSelector extends Result implements Serializable {

        private String kind;
        private HashMap labels;
        private ArrayList<String> objectList;
        private HashMap<String, String> projectList;
        private String invalidMessage;

        public AlaudaDevopsResourceSelector(String highLevelOperation, Object okind_or_list, Object qualifier) {
            super(highLevelOperation);

            if (okind_or_list instanceof List) {
                objectList = toStringList(okind_or_list);
                this.kind = null;
                this.invalidMessage = null;
                return;
            }

            if (okind_or_list instanceof Object[]) {
                objectList = new ArrayList<String>(Arrays.asList(toStringArray(okind_or_list)));
                this.kind = null;
                this.invalidMessage = null;
                return;
            }

            String kind = toSingleString(okind_or_list);
            kind = kind == null ? "all" : kind;

            if (kind.contains("/")) {
                if (qualifier != null) {
                    throw new AbortException("Unsupported selector parameter; only a single argument is permitted if name/name is specified");
                }
                String[] s = kind.split("/")
                kind = s[0];
                qualifier = s[1];
            }

            if (abbreviations.containsKey(kind)) {
                kind = abbreviations.get(kind);
            }
            if (qualifier != null) {
                if (qualifier instanceof Map) {
                    this.labels = new HashMap((Map) qualifier);
                } else {
                    // Otherwise, the qualifier is a name that is paired with the kind
                    objectList = Arrays.asList(kind + "/" + qualifier.toString());
                    kind = null;
                }
            }

            this.kind = kind != null ? kind.toLowerCase().trim() : kind;
            this.invalidMessage = null;
        }

        public AlaudaDevopsResourceSelector(String highLevelOperation, ArrayList<Object> objectList) {
            super(highLevelOperation);
            this.objectList = toStringList(objectList);
            this.invalidMessage = null;
        }

        public AlaudaDevopsResourceSelector(Result r, ArrayList<Object> objectList) {
            super(r);
            this.objectList = toStringList(objectList);
            this.invalidMessage = null;
        }

        public AlaudaDevopsResourceSelector(Result r, ArrayList<Object> objectList, HashMap<String, String> projectList) {
            this(r, objectList);
            this.projectList = projectList;
        }

        @NonCPS
        public String toString() {
            return String.format("selector([name=%s],[labels=%s],[namelist=%s],[projectlist=%s])", kind, labels, objectList, projectList)
        }

        @NonCPS
        private ArrayList selectionArgs() {
            if (invalidMessage != null && invalidMessage.length() > 0) {
                throw new AbortException(invalidMessage);
            }

            ArrayList args = new ArrayList();

            if (objectList != null) {
                objectList.each { e -> args.add(e); }
            } else {
                args.add(kind);

                if (labels != null) {
                    def labelBuilder = ""
                    Iterator<Map.Entry> i = labels.entrySet().iterator();
                    while (i.hasNext()) {
                        Map.Entry e = i.next();
                        // TODO: handle quotes, newlines, etc?
                        labelBuilder <<= sprintf("%s=%s,", e.getKey(), e.getValue());
                    }
                    labelBuilder = labelBuilder.substring(0, labelBuilder.length() - 1)
                    args.add('-l ' + labelBuilder)
                }
            }

            return args;
        }

        @NonCPS
        private ArrayList<String> flattenMap(Map pairs) {
            ArrayList<String> args = new ArrayList<>();
            if (pairs == null) {
                return args;
            }
            Iterator<Map.Entry> i = pairs.entrySet().iterator();
            while (i.hasNext()) {
                Map.Entry e = i.next();
                // TODO: handle quotes, newlines, etc?
                args.add(sprintf("%s=%s", e.getKey(), e.getValue()));
            }
            return args;
        }

        public Result delete(Object... ouserArgs) throws AbortException {
            String[] userArgs = toStringArray(ouserArgs);
            List selectionArgs = selectionArgs();
            if (kind != null && labels == null) {
                selectionArgs.add("--all");
            }

            Result r = new Result("delete");

            if (_isEmptyStatic()) {
                return r;
            }

            r.actions.add(
                    (AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("delete", selectionArgs, userArgs))
            );
            r.failIf("Error during delete");
            return r;
        }

        /**
         * If an AcpAction is performed for an empty static, no object criteria will be
         * added to the command line and oc will report an error. Instead, many operations
         * should just short circuit and not execute oc.
         * @return Detects whether an operation is being performed on an empty selector.
         */
        private boolean _isEmptyStatic() {
            return objectList != null && objectList.size() == 0;
        }

        private Result runSubVerb(String action, Map pairs, Object... ouserArgs) throws AbortException {
            String[] userArgs = toStringArray(ouserArgs);
            List verbArgs = selectionArgs();
            if (kind != null && labels == null) {
                verbArgs.add("--all");
            }
            verbArgs.addAll(flattenMap(pairs));
            Result r = new Result(action);

            if (_isEmptyStatic()) {
                return r;
            }

            r.actions.add(
                    (AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs(action, verbArgs, userArgs))
            );
            r.failIf("Error during " + action);
            return r;

        }

        public Result label(Map newLabels, Object... ouserArgs) throws AbortException {
            return runSubVerb("label", newLabels, ouserArgs);
        }

        public Result annotate(Map newAnnotations, Object... ouserArgs) throws AbortException {
            return runSubVerb("annotate", newAnnotations, ouserArgs);
        }

        public Result describe(Object... ouserArgs) throws AbortException {
            String[] userArgs = toStringArray(ouserArgs);

            Result r = new Result("describe");

            /**
             * Intentionally not checking _isEmptyStatic as the user
             * probably wants to know if the selector is empty if
             * they are trying to describe it.
             */

            Map args = buildCommonArgs("describe", selectionArgs(), userArgs);
            args.put("streamStdOutToConsolePrefix", "describe");
            r.actions.add(
                    (AcpAction.AcpActionResult) script._AcpAction(args)
            );
            r.failIf("Error during describe");
            return r;
        }

        public void watch(Closure<?> body) {

            if (_isEmptyStatic()) {
                throw new AbortException("Selector is static and empty; watch would never terminate.");
            }

            if (objectList != null && objectList.size() != 1) {
                throw new AbortException("kubectl watch does not support watching multiple named resources (watch a kind or a set of labeled objects instead).");
            }

            script._OcWatch(buildCommonArgs("get", selectionArgs(), null, "-w", "--watch-only", "-o=name")) {
                body.call(this);
            }
        }

        public boolean exists() throws AbortException {
            if (objectList != null) {
                // If object names are explicitly given, make sure they *all* exists
                return objectList.size() > 0 && count() == objectList.size()
            }
            return count() > 0
        }

        public void untilEach(int min = 1, Closure<?> body) {
            /*
             reminder from untilEach's help:
                Unless an exception is thrown by the closure, <code>untilEach</code> will not terminate
                until the number of objects selected by the receiver is greater-than or equal to the minimumCount
                and the closure body returns true
             */
            watch {
                while (true) {
                    if (it.count() < min) {
                        try {
                            // Pipeline timeouts are the correct way to abort this loop 
                            // for taking too long
                            Thread.sleep(1000);
                        } catch (Throwable t) {
                        }
                        continue;
                    }
                    boolean result = true;
                    it.withEach {
                        Object r = body.call(it);
                        if (!(r instanceof Boolean) || !((Boolean) r).booleanValue()) {
                            result = false;
                        }
                    }
                    return result;
                }
            }
        }

        private HashMap _emptyListModel() {
            HashMap el = new HashMap();
            el.put("apiVersion", "v1");
            el.put("kind", "List");
            el.put("metadata", new HashMap());
            el.put("items", new ArrayList());
            return el;
        }

        private String _asMarkup(String markupType, Map mode = null) throws AbortException {
            boolean exportable = false;
            if (mode != null) {
                exportable = (new Boolean(mode.get("exportable", new Boolean(false)))).booleanValue();
            }

            if (_isEmptyStatic()) {
                return "";
            }

            String verb = exportable ? "export" : "get"
            if (projectList == null || projectList.size() == 0) {
                AcpAction.AcpActionResult r = (AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs(verb, selectionArgs(), null, "-o=" + markupType));
                r.failIf("Unable to retrieve object markup with " + verb);
                return r.out;
            }
            if (objectList == null) {
                Map stepArgs = buildCommonArgs(verb, selectionArgs(), null, "-o=" + markupType);
                stepArgs["project"] = currentContext.getProject();
                AcpAction.AcpActionResult r = (AcpAction.AcpActionResult) script._AcpAction(stepArgs);
                r.failIf("Unable to retrieve object markup with " + verb);
                return r.out;
            }
            if (invalidMessage != null && invalidMessage.length() > 0) {
                throw new AbortException(invalidMessage);
            }
            Result result = new Result(verb);
            for (int i = 0; i < objectList.size(); i++) {
                ArrayList<String> verbArgs = new ArrayList<String>(1);
                verbArgs.add(objectList.get(i));
                Map stepArgs = buildCommonArgs(verb, verbArgs, null, "-o=" + markupType);
                String project = null;
                if (projectList != null) {
                    // add index in case same kind/name in diff projects
                    project = projectList.get(objectList.get(i) + i);
                }
                if (project == null)
                    project = currentContext.getProject();
                stepArgs["project"] = project;
                AcpAction.AcpActionResult r = (AcpAction.AcpActionResult) script._AcpAction(stepArgs);
                r.failIf("Unable to retrieve object markup with " + verb);
                result.actions.add(r);
            }
            return result.getOut();
        }

        public String asJson(Map mode = null) throws AbortException {
            return _asMarkup("json", mode);
        }

        public String asYaml(Map mode = null) throws AbortException {
            return _asMarkup("yaml", mode);
        }

        /**
         * Returns all objects selected by the receiver as a HashMap
         * modeling the associated server objects. If no objects are selected,
         * an Devops List with zero items will be returned.
         */
        private HashMap _asSingleMap(Map mode = null) throws AbortException {
            if (_isEmptyStatic()) {
                return _emptyListModel();
            }
            return serializableMap(asJson(mode));
        }

        public ArrayList<Map> objects(Map mode = null) throws AbortException {
            HashMap m = _asSingleMap(mode);
            return unwrapAlaudaDevopsList(m);
        }

        public int count() throws AbortException {
            return queryNames().size();
        }

        public HashMap<String, String> projects() {
            return projectList;
        }

        public String project() throws AbortException {
            currentContext.getProject();
        }

        public HashMap object(Map mode = null) throws AbortException {
            HashMap m = _asSingleMap(mode);
            if (m.kind == "List") {
                if (m.items == null || m.items.size() == 0) {
                    throw new AbortException("Expected single object, but found selection empty");
                }
                if (m.items != null && m.items.size() > 1) {
                    throw new AbortException("Expected single object, but found multiple in selection " + m);
                }
                m = (HashMap) ((List) m.items).get(0);
            }
            return m;
        }

        private ArrayList<String> queryNames() throws AbortException {
            if (_isEmptyStatic()) {
                return new ArrayList<String>(0);
            }

            // Otherwise, we need to ask the API server what presently matches
            AcpAction.AcpActionResult r = null;
            if (script.alaudaDevops.getCapabilities().hasIgnoredNotFound()) {
                r = (AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("get", selectionArgs(), null, "-o=name", "--ignore-not-found"));
                r.failIf("Unable to retrieve object names: " + this.toString());
            } else {
                r = (AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("get", selectionArgs(), null, "-o=name"));
                if (r.status != 0 && r.err.contains("(NotFound)")) {
                    return new ArrayList<String>();
                } else {
                    r.failIf("Unable to retrieve object names: " + this.toString());
                }
            }

            return AlaudaDevopsDSL.splitNames(r.out);
        }

        public ArrayList<String> names() throws AbortException {
            if (objectList != null) {
                return objectList;
            }
            return queryNames();
        }

        public String name() throws AbortException {
            ArrayList<String> names = names();
            if (names.size() == 0) {
                throw new AbortException("Expected single name, but found selection empty");
            }
            if (names.size() > 1) {
                throw new AbortException("Expected single name, but found multiple in selection: " + names.toString());
            }
            return names.get(0);
        }

        public Result logs(Object... ouserArgs) throws AbortException {
            String[] userArgs = toStringArray(ouserArgs);

            Result r = new Result("logs");
            List<String> names = names();
            // oc logs only supports a single object at a time, so get individual names
            for (String name : names) {
                Map args = buildCommonArgs("logs", [name], userArgs);
                args.put("streamStdOutToConsolePrefix", "logs:" + name);
                r.actions.add(
                        (AcpAction.AcpActionResult) script._AcpAction(args)
                );
            }
            r.failIf("Error running logs on at least one item: " + names.toString());
            return r;
        }

        public AlaudaDevopsResourceSelector startBuild(Object... ouserArgs) throws AbortException {
            String[] userArgs = toStringArray(ouserArgs);
            List argList = Arrays.asList(userArgs);
            boolean realTimeLogs = argList.contains("-F") || argList.contains("--follow=true") || argList.contains("--follow");

            Result r = new Result("startBuild");
            List<String> names = names();
            // only supports a single object at a time, so get individual names
            for (String name : names) {
                Map args = buildCommonArgs("start-build", [name.toString()], userArgs, "-o=name")
                if (realTimeLogs) {
                    args.put("streamStdOutToConsolePrefix", "start-build:" + name);
                }
                r.actions.add(
                        (AcpAction.AcpActionResult) script._AcpAction(args)
                );
            }
            r.failIf("Error running start-build on at least one item: " + names.toString());
            ArrayList<String> resultOutput = new ArrayList<String>();
            if (!realTimeLogs) {
                resultOutput = AlaudaDevopsDSL.splitNames(r.out)
            }
            AlaudaDevopsResourceSelector retSel = new AlaudaDevopsResourceSelector(r, resultOutput);
            retSel.projectList = projectList;
            if (realTimeLogs) {
                retSel.invalidMessage = "A valid selector cannot be created when using -F/--follow with start-build....";
            }
            return retSel;
        }

        private Result onceForEach(String operation, String verb, Object[] ouserArgs) {
            String[] userArgs = toStringArray(ouserArgs);

            Result r = new Result(operation);
            List<String> names = names();
            for (String name : names) {
                r.actions.add(
                        (AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs(verb, [name.toString()], userArgs))
                );
            }
            r.failIf("Error running " + verb + " on at least one item: " + names.toString());
            return r;
        }

        public Result cancelBuild(Object... userArgs) throws AbortException {
            return onceForEach("cancelBuild", "cancel-build", userArgs);
        }

        public Result deploy(Object... userArgs) throws AbortException {
            return onceForEach("deploy", "deploy", userArgs);
        }

        public Result scale(Object... userArgs) throws AbortException {
            return onceForEach("scale", "scale", userArgs);
        }

        public Result autoscale(Object... userArgs) throws AbortException {
            return onceForEach("autoscale", "autoscale", userArgs);
        }

        public Result expose(Object... userArgs) throws AbortException {
            return onceForEach("expose", "expose", userArgs);
        }

        public Result volume(Object... userArgs) throws AbortException {
            return onceForEach("volume", "volume", userArgs);
        }

        public Result patch(Object opatch, Object... ouserArgs) throws AbortException {
            String patch = opatch.toString();
            String[] userArgs = toStringArray(ouserArgs);

            Result r = new Result("patch")
            List<String> names = names();
            for (String name : names) {
                r.actions.add((AcpAction.AcpActionResult) script._AcpAction(buildCommonArgs("patch", [name, "-p", patch], userArgs)));
            }
            r.failIf("Error running patch on at least one item: " + names.toString());
            return r;
        }

        public <V> V withEach(Closure<V> body) {
            List<String> names = names();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                ArrayList<String> nameList = new ArrayList<String>(1);
                nameList.add(name);
                HashMap<String, String> projList = null;
                if (projectList != null) {
                    // if projectList != null then names() return objectList vs. live query
                    // add index in case same kind/name in diff projects
                    String project = projectList.get(name + i);
                    if (project != null) {
                        projList = new HashMap<String, String>();
                        // we hardcode the suffix to 0 since we are only sending 
                        // 1 item from the original namelist down ... hence the 
                        // suffix is now the index for the first entry in the array
                        projList.put(name + "0", project);
                    }
                }
                AlaudaDevopsResourceSelector selector = new AlaudaDevopsResourceSelector("withEach", nameList);
                if (projList != null)
                    selector.projectList = projList;
                body.call(selector);
            }
        }

        public AlaudaDevopsResourceSelector freeze() throws AbortException {
            AlaudaDevopsResourceSelector selector = new AlaudaDevopsResourceSelector("freeze", names());
            selector.projectList = projectList;
            return selector;
        }

        public AlaudaDevopsRolloutManager rollout() throws AbortException {
            return new AlaudaDevopsRolloutManager(this);
        }

        public AlaudaDevopsResourceSelector narrow(Object okind) throws AbortException {
            String kind = okind.toString(); // convert gstring to string if necessary
            kind = kind.toLowerCase().trim();
            String expandedKind = null;

            // Expand abbreviations
            abbreviations.containsKey(kind) && (expandedKind = abbreviations.get(kind));

            ArrayList<String> nameList = new ArrayList<String>();
            HashMap<String, String> projList = new HashMap<String, String>();
            List<String> names = names();
            HashMap<String, String> projects = projects();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                String k = name.split("/")[0]
                if (k.equals(kind) || (k + "s").equals(kind) ||
                        (kind + "s").equals(k)) {
                    nameList.add(name);
                    if (projects != null && projects.get(name) != null)
                        projList.put(name, projects.get(name));
                } else {
                    if (expandedKind != null) {
                        if (k.equals(expandedKind) || (k + "s").equals(expandedKind) ||
                                (expandedKind + "s").equals(k)) {
                            nameList.add(name);
                            if (projects != null && projects.get(name) != null)
                                projList.put(name, projects.get(name));
                        }
                    }
                }
            }

            AlaudaDevopsResourceSelector selector = new AlaudaDevopsResourceSelector("narrow", nameList);
            selector.projectList = projList;
            return selector;
        }

        public AlaudaDevopsResourceSelector union(AlaudaDevopsResourceSelector sel) throws AbortException {
            ArrayList<String> incomingNames = sel.names();
            if (incomingNames == null || incomingNames.size() == 0) {
                return this;
            }
            ArrayList<String> originalNames = this.names();
            if (originalNames == null || originalNames.size() == 0) {
                return sel;
            }
            HashSet<String> newNames = new HashSet<String>(originalNames);
            HashMap<String, String> newProjects = new HashMap<String, String>();
            HashMap<String, String> originalProjects = this.projects();
            if (originalProjects != null) {
                newProjects = originalProjects;
            }
            HashMap<String, String> incomingProjects = sel.projects();
            for (String incomingName : incomingNames) {
                if (newNames.contains(incomingName))
                    continue;
                newNames.add(incomingName);
                if (incomingProjects != null) {
                    newProjects.put(incomingNames, incomingProjects.get(incomingName));
                }
            }
            AlaudaDevopsResourceSelector selector = new AlaudaDevopsResourceSelector("union", new ArrayList<String>(newNames));
            selector.projectList = newProjects;
            return selector;
        }


        public AlaudaDevopsResourceSelector related(Object okind) throws AbortException {
            String kind = okind.toString(); // convert gstring to string if necessary
            kind = kind.toLowerCase().trim();

            // Expand abbreviations
            abbreviations.containsKey(kind) && (kind = abbreviations.get(kind));

            HashMap<String, String> labels = new HashMap<String, String>();
            ArrayList<String> newList = new ArrayList<String>();

            String[] split = name().split("/");
            String k = split[0];
            String unqualifiedName = split[1];
            abbreviations.containsKey(k) && (k = abbreviations.get(k));
            switch (k) {
                case "template":
                case "templates":
                    labels.put("template", unqualifiedName);
                    break;
                case "deploymentconfig":
                case "deploymentconfigs":
                    labels.put("deploymentconfig", unqualifiedName);
                    break;
                case "buildconfig":
                case "buildconfigs":
                    labels.put("alauda.io/build-config.name", unqualifiedName);
                    break;
                case "job":
                case "jobs":
                    labels.put("job-name", unqualifiedName);
                    break;
                default:
                    throw new AbortException("Unknown how to find resources related to kind: " + k);
            }

            AlaudaDevopsResourceSelector selector = new AlaudaDevopsResourceSelector("related", kind, labels);
            return selector;
        }

    }

    public class AlaudaDevopsRolloutManager implements Serializable {

        private final AlaudaDevopsResourceSelector selector;

        public AlaudaDevopsRolloutManager(AlaudaDevopsResourceSelector selector) {
            this.@selector = selector;
        }

        private Result runSubVerb(String subVerb, Object[] oargs, boolean streamToStdout = false) throws AbortException {
            String[] args = toStringArray(oargs);
            Result r = new Result("rollout:" + subVerb);
            selector.withEach {
                String dcName = it.name();
                List verbArgs = [subVerb, dcName];
                Map stepArgs = buildCommonArgs("rollout", verbArgs, args, null);
                stepArgs.streamStdOutToConsolePrefix = "rollout:" + subVerb + ":" + dcName;
                r.actions.add((AcpAction.AcpActionResult) script._AcpAction(stepArgs));
            }
            r.failIf(r.highLevelOperation + " returned an error");
            return r;
        }

        public Result cancel(Object... args) throws AbortException { return runSubVerb("cancel", args); }

        public Result history(Object... args) throws AbortException { return runSubVerb("history", args, true); }

        public Result latest(Object... args) throws AbortException { return runSubVerb("latest", args); }

        public Result pause(Object... args) throws AbortException { return runSubVerb("pause", args); }

        public Result resume(Object... args) throws AbortException { return runSubVerb("resume", args); }

        public Result status(Object... args) throws AbortException { return runSubVerb("status", args, true); }

        public Result undo(Object... args) throws AbortException { return runSubVerb("undo", args); }

        public Result retry(Object... args) throws AbortException { return runSubVerb("retry", args); }
    }


    private <V> V node(Closure<V> body) {
        if (script.env.NODE_NAME != null) {
            // Already inside a node block.
            body()
        } else {
            script.node {
                body()
            }
        }
    }

}
