import org.egov.jenkins.ConfigParser
import org.egov.jenkins.Utils
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import hudson.plugins.git.GitSCM
import hudson.plugins.git.UserRemoteConfig
import hudson.plugins.git.BranchSpec
import hudson.model.ParametersDefinitionProperty
import hudson.plugins.gitparameter.GitParameterDefinition
import hudson.plugins.gitparameter.SortMode
import hudson.plugins.gitparameter.SelectedValue
import hudson.model.BooleanParameterDefinition
import hudson.tasks.LogRotator

@NonCPS
def createFolders(List<String> paths) {
    Jenkins j = Jenkins.get()
    for (String folderPath : paths) {
        String[] parts = folderPath.split("/")
        ItemGroup parent = j
        for (String part : parts) {
            Item existing = parent.getItem(part)
            if (existing == null) {
                parent = parent.createProject(Folder.class, part)
            } else if (existing instanceof Folder) {
                parent = existing
            }
        }
    }
}

@NonCPS
def createOrUpdateJob(String gitUrl, String jobName) {
    Jenkins j = Jenkins.get()
    ItemGroup parent = j
    String shortName = jobName
    if (jobName.contains("/")) {
        int lastSlash = jobName.lastIndexOf("/")
        String folderPath = jobName.substring(0, lastSlash)
        shortName = jobName.substring(lastSlash + 1)
        Item item = j
        for (String seg : folderPath.split("/")) {
            item = item.getItem(seg)
        }
        parent = item
    }

    WorkflowJob job = parent.getItem(shortName)
    if (job == null) {
        job = parent.createProject(WorkflowJob.class, shortName)
    }

    job.setLogRotator(new LogRotator(-1, 5, -1, -1))

    job.definition = new CpsScmFlowDefinition(
        new GitSCM(
            [new UserRemoteConfig(gitUrl, null, null, "git_read")] as List,
            [new BranchSpec('${BRANCH}')] as List,
            false, [], [], null
        ),
        "Jenkinsfile"
    )

    def oldParams = job.getProperty(ParametersDefinitionProperty.class)
    if (oldParams != null) {
        job.removeProperty(oldParams)
    }
    job.addProperty(new ParametersDefinitionProperty(
        new GitParameterDefinition(
            "BRANCH",
            GitParameterDefinition.GitParameterType.PT_BRANCH_TAG,
            "", "", "origin/master", ".*", "*",
            SortMode.ASCENDING_SMART, SelectedValue.DEFAULT, true, 5
        ),
        new BooleanParameterDefinition("ALT_REPO_PUSH", false, "Check to push images to GCR")
    ))

    job.save()
}

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
            script {
                createFolders(foldersList)
                for (Map.Entry<String, List<JobConfig>> entry : jobConfigMap.entrySet()) {
                    String gitUrl = entry.getKey()
                    for (int i = 0; i < entry.getValue().size(); i++) {
                        createOrUpdateJob(gitUrl, entry.getValue().get(i).getName())
                    }
                }
            }
        }

        stage('Creating Repositories in DockerHub') {
                    withEnv(["REPO_LIST=${repoList}"
                    ]) {
                        container(name: 'build-utils', shell: '/bin/sh') {
                            sh (script:'sh /tmp/scripts/create_repo.sh')
                        }
                    }
        }

    }

}
}
