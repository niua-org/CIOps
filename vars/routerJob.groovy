import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig

import groovy.json.JsonSlurper

def call(Map params) {
    def gitUrl = params.gitUrl
    def credentialsId = params.credentialsId ?: 'git_read'  // SSH key - kept as-is, unused by this job now
    def apiCredentialsId = params.apiCredentialsId ?: 'git_read_token'  // GitHub PAT - required for Contents API call
    def configFile = params.configFile ?: 'build/build-config.yml'

    // Label for a lightweight always-on agent / controller node.
    // No container/pod needed here — there's no checkout and no heavy build tooling,
    // just a tiny HTTP call + string matching.
    def agentLabel = params.agentLabel ?: 'built-in'

    // Fixed downstream branch param — always niua-dev-2.0 regardless of trigger branch
    final String DOWNSTREAM_BRANCH = 'origin/niua-dev-2.0'

    node(agentLabel) {

        stage('Parse Webhook Payload') {
            String ref = env.REF ?: ''
            String after = env.AFTER ?: ''

            if (!ref || !after) {
                error "This pipeline is designed to run via webhook trigger only.\n" +
                      "Please configure a GitHub webhook pointing to:\n" +
                      "  <jenkins>/generic-webhook-trigger/invoke?token=UPYOG-NIUA-Vikash"
            }

            String branch = ref.replace('refs/heads/', '').replace('refs/tags/', '')
            echo "Webhook: ref=${ref}, branch=${branch}, after=${after}"
            echo "Downstream jobs will be triggered with fixed BRANCH=${DOWNSTREAM_BRANCH}"

            env.RESOLVED_AFTER = after
        }

        stage('Get Changed Files (from webhook payload)') {
            // No git diff, no checkout — changed files come straight from the
            // GitHub webhook's own commit list. This is what used to cost 1m46s.
            List<String> fileChanges = []

            ['ADDED_FILES', 'MODIFIED_FILES', 'REMOVED_FILES'].each { varName ->
                String raw = env[varName]
                if (raw) {
                    try {
                        def parsed = new JsonSlurper().parseText(raw)
                        // Generic Webhook Trigger may return a flat list or list-of-lists
                        // depending on plugin version's JSONPath flattening behavior.
                        parsed.each { entry ->
                            if (entry instanceof List) {
                                fileChanges.addAll(entry.collect { it.toString() })
                            } else if (entry) {
                                fileChanges.add(entry.toString())
                            }
                        }
                    } catch (Exception e) {
                        echo "WARNING: could not parse ${varName} as JSON: ${e.message}"
                    }
                }
            }

            fileChanges = fileChanges.unique()

            if (fileChanges.isEmpty()) {
                echo "No changed files found in webhook payload. " +
                     "Check Generic Webhook Trigger config for ADDED_FILES / MODIFIED_FILES / REMOVED_FILES JSONPath."
            } else {
                echo "Changed files (${fileChanges.size()}):\n${fileChanges.join('\n').take(2000)}"
            }

            // Comma-with-marker join, safe for typical repo file paths (no commas expected).
            env.CHANGED_FILES_JOINED = fileChanges.join(',;,')
        }

        stage('Fetch build-config.yml (lightweight, no checkout)') {
            // Single HTTPS call to the GitHub Contents API — fetches just this one
            // file at the exact triggering commit, no clone of the repo at all.
            // NOTE: uses a GitHub PAT (apiCredentialsId), NOT the SSH key (credentialsId/git_read).
            // SSH keys can't be used for a plain HTTPS REST call.
            withCredentials([usernamePassword(credentialsId: apiCredentialsId,
                                               usernameVariable: 'GIT_USER',
                                               passwordVariable: 'GIT_TOKEN')]) {
                String repoPath = gitUrl
                    .replaceAll(/^git@github\.com:/, '')
                    .replaceAll(/^https:\/\/github\.com\//, '')
                    .replaceAll(/\.git$/, '')

                String apiUrl = "https://api.github.com/repos/${repoPath}/contents/${configFile}?ref=${env.RESOLVED_AFTER}"

                int httpStatus = sh(
                    script: """
                        curl -s -o config_response.yml -w "%{http_code}" \\
                            -H "Authorization: token \$GIT_TOKEN" \\
                            -H "Accept: application/vnd.github.raw" \\
                            "${apiUrl}"
                    """,
                    returnStdout: true
                ).trim() as int

                if (httpStatus != 200) {
                    error "Failed to fetch ${configFile} from GitHub API (HTTP ${httpStatus}). " +
                          "Check credentialsId, repo path (${repoPath}), and ref (${env.RESOLVED_AFTER})."
                }

                env.CONFIG_LOCAL_PATH = "${pwd()}/config_response.yml"
            }
        }

        stage('Match Jobs to Changes') {
            List<String> fileChanges = env.CHANGED_FILES_JOINED
                ? env.CHANGED_FILES_JOINED.split(',;,').toList().findAll { it }
                : []

            if (fileChanges.isEmpty()) {
                echo "No file changes to match. Nothing to trigger."
                env.JOBS_TO_TRIGGER = ''
                return
            }

            def yaml = readYaml file: env.CONFIG_LOCAL_PATH
            List<JobConfig> jobConfigs = ConfigParser.populateConfigs(yaml.config, env)
            echo "Loaded ${jobConfigs.size()} job configs from ${configFile}"

            Set<String> matchingJobNames = new LinkedHashSet<>()
            Set<String> matchedFiles = new HashSet<>()
            for (JobConfig jobConfig : jobConfigs) {
                String jobName = jobConfig.getName()
                for (BuildConfig buildConfig : jobConfig.getBuildConfigs()) {
                    String workDir = buildConfig.getWorkDir()
                    for (String changedFile : fileChanges) {
                        if (changedFile.startsWith(workDir + "/") || changedFile == workDir) {
                            matchingJobNames.add(jobName)
                            matchedFiles.add(changedFile)
                            echo "  Match: ${changedFile} → ${jobName}"
                            break
                        }
                    }
                }
            }

            def unmatchedFiles = fileChanges.findAll { !matchedFiles.contains(it) }

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
                echo "Ignoring non-service files:\n${unmatchedFiles.join('\n')}"
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
                            booleanParam(name: 'WANNA_DEPLOY', value: true)
                        ]
                }
            } else {
                echo "No matching jobs to trigger."
            }
        }
    }
}