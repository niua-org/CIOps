import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.BuildConfig
import org.egov.jenkins.models.JobConfig

import static org.egov.jenkins.ConfigParser.getCommonBasePath

library 'ci-libs'

/*
 * Builds and deploys ALL services under a given category.
 *
 * Reads the root build-config.yml, filters to only services matching
 * the category prefix (e.g., "builds/upyog/business-services/"), builds
 * each one sequentially with kaniko, collects all image names, and
 * passes the comma-separated list to deploy-to-qa in a single call.
 *
 * Parameters:
 *   category   - category folder name (e.g., "business-services", "core-services")
 *   repoUrl    - git repository URL to checkout (passed by jobBuilder)
 *   branch     - branch to build (required, no default — will error if empty)
 *   configFile - path to build-config.yml (default: build/build-config.yml)
 *   wannaDeploy - whether to trigger deployment after build (default: false)
 */
def call(Map pipelineParams) {
    String category = pipelineParams.category
    String repoUrl = pipelineParams.repoUrl
    String branch = pipelineParams.branch
    if (!branch?.trim()) {
        error "branch parameter is required — e.g., branch: 'niua-dev-2.0'"
    }
    String configFile = pipelineParams.configFile ?: 'build/build-config.yml'

    if (!category) {
        error "category parameter is required — e.g., category: 'business-services'"
    }

    podTemplate(yaml: """
kind: Pod
metadata:
  name: kaniko
spec:
  containers:
  - name: kaniko
    image: gcr.io/kaniko-project/executor:v1.17.0-debug
    imagePullPolicy: IfNotPresent
    workingDir: /home/jenkins/agent
    command:
    - /busybox/cat
    tty: true
    env:
      - name: GIT_ACCESS_TOKEN
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: gitReadAccessToken
      - name: token
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: gitReadAccessToken
      - name: SLACK_WEBHOOK
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: slackWebhook
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /kaniko/.docker
      - name: kaniko-cache
        mountPath: /cache
    resources:
      requests:
        memory: "3Gi"
        cpu: "750m"
        ephemeral-storage: "10Gi"
      limits:
        memory: "6Gi"
        cpu: "1500m"
        ephemeral-storage: "20Gi"
  - name: git
    image: docker.io/egovio/builder:2-64da60a1-version_script_update-NA
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
  - name: jnlp
    env:
      - name: SLACK_WEBHOOK
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: slackWebhook
      - name: SLACK_WEBHOOK_FAIL
        valueFrom:
           secretKeyRef:
            name: jenkins-credentials
            key: slackWebhookFail
  volumes:
  - name: kaniko-cache
    persistentVolumeClaim:
      claimName: kaniko-cache-claim
      readOnly: true
  - name: jenkins-docker-cfg
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: dockerConfigJson
              path: config.json
"""
    ) {
        node(POD_LABEL) {
            // Single checkout for all services (explicit git, no "checkout scm")
            def scmVars
            dir('repo') {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "*/${branch}"]],
                    userRemoteConfigs: [[url: repoUrl, credentialsId: 'git_read_token']],
                    extensions: []
                ])
                scmVars = [
                    GIT_COMMIT: sh(script: 'git rev-parse HEAD', returnStdout: true).trim(),
                    GIT_BRANCH: branch
                ]
            }
            // Copy repo contents to workspace root so workDir paths resolve correctly
            sh 'cp -a repo/* . 2>/dev/null || true'
            String REPO_NAME = env.REPO_NAME ? env.REPO_NAME : "docker.io/nudmcdg"
            def yaml = readYaml file: configFile

            // Parse ALL configs (not filtered by JOB_NAME)
            List<JobConfig> allJobConfigs = ConfigParser.populateConfigs(yaml.config, env)

            // Filter to only services in this category (e.g., builds/upyog/business-services/*)
            String prefix = "builds/upyog/${category}/"
            List<JobConfig> categoryJobs = allJobConfigs.findAll { jc ->
                jc.getName().startsWith(prefix)
            }

            if (categoryJobs.isEmpty()) {
                error "No services found for category '${category}' (prefix: '${prefix}')"
            }

            echo "Found ${categoryJobs.size()} services in category '${category}':"
            categoryJobs.each { echo "  - ${it.getName()}" }

            List<String> builtImages = []
            String slackTimestamp = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))
            String commitMessage = scmVars.GIT_COMMIT ? scmVars.GIT_COMMIT.take(8) : ''
            // Keep the branch name for image tagging (scmVars BRANCH gets set per-service in the build loop)
            def effectiveBranch = branch

            int total = categoryJobs.size()
            int current = 0
            int failedCount = 0
            List<String> failedServices = []

            // Build each service one by one, collecting image names
            for (JobConfig jobConfig : categoryJobs) {
                current++
                String serviceName = jobConfig.getName().split('/').last()
                echo "=== [${current}/${total}] Building ${serviceName} ==="

                try {
                    stage("Build ${serviceName}") {

                        // Get version and commit info for this service
                        stage("${serviceName}: Parse Git Commit") {
                            withEnv(["BUILD_PATH=${jobConfig.getBuildConfigs().get(0).getWorkDir()}",
                                     "PATH=alpine:$PATH"
                            ]) {
                                container(name: 'git', shell: '/bin/sh') {
                                    scmVars['VERSION'] = sh(script:
                                        '/scripts/get_application_version.sh ${BUILD_PATH}',
                                        returnStdout: true).trim()
                                    scmVars['ACTUAL_COMMIT'] = sh(script:
                                        '/scripts/get_folder_commit.sh ${BUILD_PATH}',
                                        returnStdout: true).trim()
                                    scmVars['BRANCH'] = scmVars['GIT_BRANCH'].replaceFirst("origin/", "")
                                }
                            }
                        }

                        // Build all images for this service (app + db) with kaniko
                        stage("${serviceName}: Build") {
                            withEnv(["PATH=/busybox:/kaniko:$PATH"]) {
                                container(name: 'kaniko', shell: '/busybox/sh') {
                                    for (BuildConfig buildConfig : jobConfig.getBuildConfigs()) {
                                        if (!fileExists(buildConfig.getWorkDir()) || !fileExists(buildConfig.getDockerFile())) {
                                            throw new Exception("Working directory / dockerfile does not exist for ${buildConfig.getImageName()}!")
                                        }

                                        String workDir = buildConfig.getWorkDir().replaceFirst(
                                            getCommonBasePath(buildConfig.getWorkDir(), buildConfig.getDockerFile()), "./")

                                        String image
                                        if (scmVars.BRANCH.equalsIgnoreCase("master")) {
                                            image = "${REPO_NAME}/${buildConfig.getImageName()}:v${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}"
                                        } else {
                                            image = "${REPO_NAME}/${buildConfig.getImageName()}:${scmVars.BRANCH}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}"
                                        }

                                        String noPushImage = env.NO_PUSH ?: "false"

                                        sh """
                                            echo "Building image: ${image}"
                                            /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} \
                                                -c `pwd`/${buildConfig.getContext()} \
                                                --build-arg WORK_DIR=${workDir} \
                                                --custom-platform=linux/amd64 \
                                                --build-arg token=\$GIT_ACCESS_TOKEN \
                                                --cache=true --cache-dir=/cache \
                                                --single-snapshot=false \
                                                --snapshotMode=redo \
                                                --destination=${image} \
                                                --no-push=${noPushImage}
                                        """
                                        echo "${image} pushed successfully!"

                                        // Collect the main app image (skip -db images for deploy)
                                        if (!buildConfig.getImageName().endsWith("-db")) {
                                            builtImages.add(image)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception err) {
                    // Track failure but continue building remaining services
                    failedCount++
                    failedServices.add(serviceName)
                    echo "ERROR: Build failed for ${serviceName}: ${err.message}"

                    // Send per-service failure notification to fail channel
                    def slackBlocks = [
                        [type: 'header', text: [type: 'plain_text', text: "❌ Category Build Failed: ${category}"]],
                        [type: 'section', fields: [
                            [type: 'mrkdwn', text: "*Service:*\n${serviceName}"],
                            [type: 'mrkdwn', text: "*Progress:*\n${current}/${total}"]
                        ]],
                        [type: 'section', text: [type: 'mrkdwn', text: "*Error:*\n${err.message.take(300)}"]],
                        [type: 'context', elements: [
                            [type: 'mrkdwn', text: "🕐 ${slackTimestamp} UTC"]
                        ]]
                    ]
                    def slackPayload = groovy.json.JsonOutput.toJson([attachments: [[color: 'danger', blocks: slackBlocks]]])
                    writeFile file: 'slack-payload.json', text: slackPayload
                    sh "curl -s -X POST -H 'Content-type: application/json' --data @slack-payload.json \${SLACK_WEBHOOK_FAIL} || true"
                }
            }

            // Print build summary
            echo "========================================"
            echo "  BUILD SUMMARY - ${category}"
            echo "========================================"
            echo "  Total services: ${total}"
            echo "  Built:         ${builtImages.size()}"
            echo "  Failed:        ${failedCount}"
            if (failedCount > 0) {
                echo "  Failed list:   ${failedServices.join(', ')}"
            }
            echo "----------------------------------------"
            echo "  Images built and pushed:"
            builtImages.each { img -> echo "    - ${img}" }
            echo "========================================"

            // Deploy all successfully built images in a single call
            boolean wannaDeploy = params.wannaDeploy ?: env.WANNA_DEPLOY?.toBoolean() ?: false
            if (wannaDeploy && !builtImages.isEmpty()) {
                String imagesParam = builtImages.join(", ")
                echo "=== Deploying ${builtImages.size()} images to QA ==="
                echo "Images: ${imagesParam}"

                try {
                    stage("Deploy to QA") {
                        build(
                            job: "deployments/deploy-to-qa",
                            wait: true,
                            parameters: [
                                string(name: "Images", value: imagesParam)
                            ]
                        )
                    }

                    // Success notification (warning if any service failed)
                    def slackBlocks = [
                        [type: 'header', text: [type: 'plain_text', text: "✅ ${category} — Build + Deploy Complete"]],
                        [type: 'section', fields: [
                            [type: 'mrkdwn', text: "*Services built:*\n${builtImages.size()}/${total}"],
                            [type: 'mrkdwn', text: "*Commit:*\n${commitMessage}"]
                        ]],
                        [type: 'context', elements: [
                            [type: 'mrkdwn', text: "<${env.BUILD_URL}|View Build>  🕐 ${slackTimestamp} UTC"]
                        ]]
                    ]
                    if (failedCount > 0) {
                        slackBlocks.add([type: 'section', text: [type: 'mrkdwn', text: "*Failed services:*\n${failedServices.join(', ')}"]])
                    }
                    def slackPayload = groovy.json.JsonOutput.toJson([attachments: [[color: failedCount > 0 ? 'warning' : 'good', blocks: slackBlocks]]])
                    writeFile file: 'slack-payload.json', text: slackPayload
                    sh "curl -s -X POST -H 'Content-type: application/json' --data @slack-payload.json \${SLACK_WEBHOOK} || true"

                } catch (Exception deployErr) {
                    // Deploy failure notification to fail channel
                    def slackBlocks = [
                        [type: 'header', text: [type: 'plain_text', text: "⚠️ ${category} — Build OK, Deploy Failed"]],
                        [type: 'section', fields: [
                            [type: 'mrkdwn', text: "*Images:*\n`${imagesParam.take(200)}`"],
                            [type: 'mrkdwn', text: "*Error:*\n${deployErr.message.take(300)}"]
                        ]],
                        [type: 'context', elements: [
                            [type: 'mrkdwn', text: "🕐 ${slackTimestamp} UTC"]
                        ]]
                    ]
                    def slackPayload = groovy.json.JsonOutput.toJson([attachments: [[color: 'danger', blocks: slackBlocks]]])
                    writeFile file: 'slack-payload.json', text: slackPayload
                    sh "curl -s -X POST -H 'Content-type: application/json' --data @slack-payload.json \${SLACK_WEBHOOK_FAIL} || true"
                    throw deployErr
                }
            } else {
                echo "No images built. Skipping deploy."
            }

            // Fail the build if any service failed
            if (failedCount > 0) {
                error "${failedCount} service(s) failed in category '${category}': ${failedServices.join(', ')}"
            }

            // Clean workspace from controller PVC
            deleteDir()
        }
    }
}
