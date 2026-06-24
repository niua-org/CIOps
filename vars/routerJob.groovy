import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig

import groovy.json.JsonSlurper

def call(Map params) {
    def gitUrl = params.gitUrl
    def credentialsId = params.credentialsId ?: 'git_read'
    def configFile = params.configFile ?: './build/build-config.yml'

    podTemplate(yaml: """
kind: Pod
metadata:
  name: router
spec:
  containers:
  - name: git
    image: docker.io/egovio/builder:2-64da60a1-version_script_update-NA
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
    resources:
      requests:
        memory: "512Mi"
        cpu: "200m"
      limits:
        memory: "1024Mi"
        cpu: "500m"
"""
    ) {
        node(POD_LABEL) {
            container(name: 'git', shell: '/bin/sh') {

                // --- Read webhook payload vars (populated by Generic Webhook Trigger) ---
                String ref = env.REF ?: ''
                String before = env.BEFORE ?: ''
                String after = env.AFTER ?: ''

                boolean isManualTrigger = !ref || !after
                if (isManualTrigger) {
                    echo "WARNING: Triggered manually — no webhook payload received."
                    echo "Set up a GitHub webhook → <jenkins>/generic-webhook-trigger/invoke?token=UPYOG-NIUA-Vikash"
                    echo "Falling back to latest master branch for this run."
                }

                String branch = isManualTrigger ? 'master' :
                    ref.replace('refs/heads/', '').replace('refs/tags/', '')
                String targetCommit = isManualTrigger ? '' : after
                echo "Branch: ${branch}, commit: ${targetCommit ?: 'latest master'}"

                stage('Checkout') {
                    dir('repo') {
                        if (targetCommit) {
                            checkout changelog: false, poll: false,
                                scm: [
                                    $class: 'GitSCM',
                                    branches: [[name: targetCommit]],
                                    userRemoteConfigs: [[url: gitUrl, credentialsId: credentialsId]]
                                ]
                        } else {
                            git url: gitUrl, credentialsId: credentialsId, branch: 'master'
                        }
                    }
                }

                stage('Get Changed Files') {
                    dir('repo') {
                        String changedFiles = ""
                        if (before && before !=~ /^0+$/) {
                            echo "Comparing ${before}..${after}"
                            try {
                                changedFiles = sh(
                                    script: "git diff --name-only ${before}..${after}",
                                    returnStdout: true
                                ).trim()
                            } catch (Exception e) {
                                echo "'before' commit not in local clone — fetching it"
                                sh "git fetch origin ${before}"
                                changedFiles = sh(
                                    script: "git diff --name-only ${before}..${after}",
                                    returnStdout: true
                                ).trim()
                            }
                        } else {
                            echo isManualTrigger ?
                                "Manual trigger — checking files changed in last commit..." :
                                "New branch or first push — no 'before' commit to compare."
                            try {
                                changedFiles = sh(
                                    script: 'git diff --name-only HEAD~1..HEAD',
                                    returnStdout: true
                                ).trim()
                            } catch (Exception e) {
                                echo "Single commit repo or no history — checking all files"
                                changedFiles = sh(
                                    script: 'git ls-files',
                                    returnStdout: true
                                ).trim()
                            }
                        }
                        env.CHANGED_FILES = changedFiles
                        echo "Changed files (${changedFiles.readLines().size()}):\n${changedFiles.take(2000)}"
                    }
                }

                stage('Match Jobs to Changes') {
                    dir('repo') {
                        def fileChanges = env.CHANGED_FILES.tokenize('\n').collect { it.trim() }.findAll { it }
                        echo "Parsed ${fileChanges.size()} changed file paths"

                        if (fileChanges.isEmpty()) {
                            echo "No file changes detected."
                            env.JOBS_TO_TRIGGER = ''
                            return
                        }

                        def yaml = readYaml file: configFile
                        List<JobConfig> jobConfigs = ConfigParser.populateConfigs(yaml.config, env)
                        echo "Loaded ${jobConfigs.size()} job configs from build-config.yml"

                        Set<String> matchingJobNames = new LinkedHashSet<>()
                        for (JobConfig jobConfig : jobConfigs) {
                            String jobName = jobConfig.getName()
                            for (BuildConfig buildConfig : jobConfig.getBuildConfigs()) {
                                String workDir = buildConfig.getWorkDir()
                                String context = buildConfig.getContext().replaceAll('^\\./', '')
                                String dockerFile = buildConfig.getDockerFile()
                                for (String changedFile : fileChanges) {
                                    if (changedFile.startsWith(workDir) ||
                                        changedFile.startsWith(context) ||
                                        changedFile.startsWith(dockerFile)) {
                                        matchingJobNames.add(jobName)
                                        echo "  Match: ${changedFile} → ${jobName}"
                                        break
                                    }
                                }
                            }
                        }

                        env.JOBS_TO_TRIGGER = matchingJobNames.join(',')
                        echo "Matching jobs (${matchingJobNames.size()}): ${env.JOBS_TO_TRIGGER ?: '(none)'}"
                    }
                }

                stage('Trigger Jobs') {
                    if (env.JOBS_TO_TRIGGER?.trim()) {
                        List<String> jobs = env.JOBS_TO_TRIGGER.split(',')
                        echo "Triggering ${jobs.size()} job(s) on branch '${branch}'"
                        for (String jobName : jobs) {
                            echo "  → ${jobName}"
                            build job: jobName,
                                wait: false,
                                parameters: [
                                    string(name: 'BRANCH', value: branch),
                                    booleanParam(name: 'ALT_REPO_PUSH', value: false),
                                    booleanParam(name: 'WANNA_DEPLOY', value: false)
                                ]
                        }
                    } else {
                        echo "No matching jobs to trigger."
                    }
                }
            }
        }
    }
}
