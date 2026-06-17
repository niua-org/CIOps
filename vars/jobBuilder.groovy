import org.egov.jenkins.ConfigParser
import org.egov.jenkins.Utils
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig

def call(Map params) {

    podTemplate(yaml: """
kind: Pod
metadata:
  name: build-utils
spec:
  containers:
  - name: build-utils
    image: egovio/build-utils:7-master-95e76687
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
    env:
      - name: DOCKER_UNAME
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: dockerUserName
      - name: DOCKER_UPASS
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: dockerPassword
      - name: DOCKER_NAMESPACE
        value: nudmcdg
      - name: DOCKER_GROUP_NAME  
        value: dev
    resources:
      requests:
        memory: "768Mi"
        cpu: "250m"
      limits:
        memory: "1024Mi"
        cpu: "500m"                
"""
    ) {
        node(POD_LABEL) {
        
        List<String> gitUrls = params.urls;
        String configFile = './build/build-config.yml';
        Map<String,List<JobConfig>> jobConfigMap=new HashMap<>();
        List<String> allJobConfigs = new ArrayList<>();

        for (int i = 0; i < gitUrls.size(); i++) {
            String dirName = Utils.getDirName(gitUrls[i]);
            dir(dirName) {
                 git url: gitUrls[i], credentialsId: 'git_read'
                 def yaml = readYaml file: configFile;
                 List<JobConfig> jobConfigs = ConfigParser.populateConfigs(yaml.config, env);
                 jobConfigMap.put(gitUrls[i],jobConfigs);
                 allJobConfigs.addAll(jobConfigs);
            }
        }
        
        Set<String> repoSet = new HashSet<>();
        String repoList = "";

        List<String> foldersList = Utils.foldersToBeCreatedOrUpdated(allJobConfigs, env);

        for (Map.Entry<String, List<JobConfig>> entry : jobConfigMap.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                for (int j = 0; j < entry.getValue().get(i).getBuildConfigs().size(); j++) {
                    repoSet.add(entry.getValue().get(i).getBuildConfigs().get(j).getImageName())
                }
            }
        }
        repoList = String.join(",", repoSet);

        stage('Building jobs') {
            String jkUrl = (env.JENKINS_URL ?: "http://test-jenkins.jenkins.svc.cluster.local:8080/").replaceAll("/+\$", "") + "/"
            String pass = sh(script: "cat /run/secrets/additional/chart-admin-password", returnStdout: true).trim()
            String auth = "admin:${pass}"

            // Get CSRF crumb
            String crumb = sh(script: "curl -s -u '${auth}' '${jkUrl}crumbIssuer/api/xml?xpath=concat(//crumbRequestField,':',//crumb)'", returnStdout: true).trim()

            // Create folders
            for (int j = 0; j < foldersList.size(); j++) {
                List<String> parts = foldersList[j].tokenize("/")
                String parentPath = ""
                for (int k = 0; k < parts.size(); k++) {
                    String currentPath = parentPath.isEmpty() ? parts[k] : parentPath + "/" + parts[k]
                    String exists = sh(script: "curl -s -o /dev/null -w '%{http_code}' -u '${auth}' '${jkUrl}job/${currentPath}/api/json'", returnStdout: true).trim()
                    if (exists == "404") {
                        String url = parentPath.isEmpty() ? "${jkUrl}createItem?name=${parts[k]}" : "${jkUrl}job/${parentPath}/createItem?name=${parts[k]}"
                        sh(script: "curl -s -X POST -u '${auth}' -H 'Content-Type: application/xml' -H '${crumb}' -d '<com.cloudbees.hudson.plugins.folder.Folder><description></description></com.cloudbees.hudson.plugins.folder.Folder>' '${url}'")
                    }
                    parentPath = currentPath
                }
            }

            // Create pipeline jobs
            int jobIdx = 0
            for (Map.Entry<String, List<JobConfig>> entry : jobConfigMap.entrySet()) {
                String gitUrl = entry.getKey()
                for (int i = 0; i < entry.getValue().size(); i++) {
                    String jobName = entry.getValue().get(i).getName()
                    String shortName = jobName
                    String parentFolder = ""
                    if (jobName.contains("/")) {
                        int lastSlash = jobName.lastIndexOf("/")
                        parentFolder = jobName.substring(0, lastSlash)
                        shortName = jobName.substring(lastSlash + 1)
                    }

                    String createUrl = parentFolder ? "${jkUrl}job/${parentFolder}/createItem?name=${shortName}" : "${jkUrl}createItem?name=${jobName}"

                    // Write XML to workspace
                    String xml = """<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <description></description>
  <keepDependencies>false</keepDependencies>
  <properties>
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions>
        <hudson.model.StringParameterDefinition>
          <name>BRANCH</name>
          <defaultValue>origin/master</defaultValue>
          <description>Branch to build</description>
        </hudson.model.StringParameterDefinition>
        <hudson.model.BooleanParameterDefinition>
          <name>ALT_REPO_PUSH</name>
          <defaultValue>false</defaultValue>
          <description>Check to push images to GCR</description>
        </hudson.model.BooleanParameterDefinition>
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>${gitUrl}</url>
          <credentialsId>git_read</credentialsId>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>\${BRANCH}</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
    </scm>
    <scriptPath>Jenkinsfile</scriptPath>
    <lightweight>false</lightweight>
  </definition>
  <disabled>false</disabled>
</flow-definition>"""
                    writeFile file: "job-${jobIdx}-${BUILD_NUMBER}.xml", text: xml

                    String exists = sh(script: "curl -s -o /dev/null -w '%{http_code}' -u '${auth}' '${jkUrl}job/${jobName}/api/json'", returnStdout: true).trim()

                    if (exists != "404") {
                        sh(script: "curl -s -X POST -u '${auth}' -H 'Content-Type: application/xml' -H '${crumb}' --data-binary @${WORKSPACE}/job-${jobIdx}-${BUILD_NUMBER}.xml '${jkUrl}job/${jobName}/config.xml'")
                    } else {
                        sh(script: "curl -s -X POST -u '${auth}' -H 'Content-Type: application/xml' -H '${crumb}' --data-binary @${WORKSPACE}/job-${jobIdx}-${BUILD_NUMBER}.xml '${createUrl}'")
                    }

                    jobIdx++
                }
            }
        }

        stage('Creating Repositories in DockerHub') {
            withEnv(["REPO_LIST=${repoList}"]) {
                container(name: 'build-utils', shell: '/bin/sh') {
                    sh (script:'sh /tmp/scripts/create_repo.sh')
                }
            }
        }

    }

}
}
