import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig

import groovy.json.JsonSlurper

def call(Map params) {
    def gitUrl = params.gitUrl
    def credentialsId = params.credentialsId ?: 'git_read'
    def configFile = params.configFile ?: './build/build-config.yml'

    // Folder that build-config.yml lives in — used for sparse checkout.
    // e.g. if configFile = './build/build-config.yml', this resolves to 'build'
    String configDir = configFile.replaceAll('^\\./', '').tokenize('/').dropRight(1).join('/')
    if (!configDir) {
        configDir = '.' // config file is at repo root
    }

    // Fixed downstream branch param — always niua-dev-2.0 regardless of trigger branch
    final String DOWNSTREAM_BRANCH = 'origin/niua-dev-2.0'

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

                if (!ref || !after) {
                    error "This pipeline is designed to run via webhook trigger only.\n" +
                          "Please configure a GitHub webhook pointing to:\n" +
                          "  <jenkins>/generic-webhook-trigger/invoke?token=UPYOG-NIUA-Vikash"
                }

                // Kept only for logging — no longer used for the downstream BRANCH param
                String gitParamBranch = ref.startsWith('refs/tags/')
                    ? ref.replace('refs/tags/', '')
                    : "origin/${ref.replace('refs/heads/', '')}"

                String branch = gitParamBranch.replace('origin/', '')
                echo "Webhook: ref=${ref}, branch=${branch}, after=${after}"
                echo "Downstream jobs will be triggered with fixed BRANCH=${DOWNSTREAM_BRANCH}"

                stage('Checkout at Triggered Commit') {
                    dir('repo') {
                        checkout changelog: false, poll: false,
                            scm: [
                                $class: 'GitSCM',
                                branches: [[name: after]],
                                userRemoteConfigs: [[url: gitUrl, credentialsId: credentialsId]],
                                extensions: [
                                    // Shallow clone — we only need recent history for the diff,
                                    // not the full repo history. 50 gives the 'before not found'
                                    // fallback below enough room before it needs to re-fetch.
                                    [$class: 'CloneOption',
                                        shallow: true,
                                        depth: 50,
                                        noTags: true,
                                        honorRefspec: false,
                                        timeout: 10],
                                    [$class: 'CheckoutOption', timeout: 10],
                                    // Sparse checkout — diff is computed from git objects
                                    // (.git), so the working tree only needs to contain the
                                    // config folder, not every microservice's files.
                                    [$class: 'SparseCheckoutPaths',
                                        sparseCheckoutPaths: [[path: configDir]]]
                                ]
                            ]
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
                                echo "'before' commit not in local shallow clone — fetching it"
                                sh "git fetch --depth=50 origin ${before}"
                                changedFiles = sh(
                                    script: "git diff --name-only ${before}..${after}",
                                    returnStdout: true
                                ).trim()
                            }
                        } else {
                            echo "New branch or first push — no 'before' commit to compare. Checking last commit..."
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
                        Set<String> matchedFiles = new HashSet<>()
                        for (JobConfig jobConfig : jobConfigs) {
                            String jobName = jobConfig.getName()
                            for (BuildConfig buildConfig : jobConfig.getBuildConfigs()) {
                                echo """
                                    JOB=${jobName}
                                    WORKDIR=${buildConfig.getWorkDir()}
                                    CONTEXT=${buildConfig.getContext()}
                                    DOCKERFILE=${buildConfig.getDockerFile()}
                                """
                                String workDir = buildConfig.getWorkDir()
                                String context = buildConfig.getContext().replaceAll('^\\./', '')
                                String dockerFile = buildConfig.getDockerFile()
                                for (String changedFile : fileChanges) {
                                    if (changedFile.startsWith(workDir + "/") ||
                                        changedFile == workDir) {
                                        matchingJobNames.add(jobName)
                                        matchedFiles.add(changedFile)
                                        echo "  Match: ${changedFile} → ${jobName}"
                                        break
                                    }
                                }
                            }
                        }

                        def unmatchedFiles = fileChanges.findAll {
                            !matchedFiles.contains(it)
                        }

                        if (matchingJobNames.isEmpty()) {
                            error """
                                No Jenkins job mapping found for changed files:

                                ${unmatchedFiles.join('\n')}

                                Changes are not inside any configured service folder.
                            """
                        }

                        env.JOBS_TO_TRIGGER = matchingJobNames.join(',')

                        echo "Matching jobs (${matchingJobNames.size()}): ${env.JOBS_TO_TRIGGER}"

                        if (unmatchedFiles) {
                            echo """
                                Ignoring non-service files:

                                ${unmatchedFiles.join('\n')}
                            """
                        }
                    }
                }

                stage('Trigger Jobs') {
                    if (env.JOBS_TO_TRIGGER?.trim()) {
                        List<String> jobs = env.JOBS_TO_TRIGGER.split(',')
                        echo "Triggering ${jobs.size()} job(s) on '${DOWNSTREAM_BRANCH}'"
                        for (String jobName : jobs) {
                            echo "  → ${jobName}"
                            build job: jobName,
                                wait: false,
                                parameters: [
                                    string(name: 'BRANCH', value: DOWNSTREAM_BRANCH),
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