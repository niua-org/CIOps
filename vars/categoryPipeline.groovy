import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.JobConfig

library 'ci-libs'

/*
 * Triggers each per-service pipeline job in a category, one by one.
 * Continues on failure — failed services are skipped, remaining ones proceed.
 * Sends a Slack summary with ✅ / ❌ status for each service.
 *
 * Parameters:
 *   category   - category folder name (e.g., "business-services", "core-services")
 *   repoUrl    - git repository URL (passed by jobBuilder)
 *   branch     - branch to build (required)
 */
def call(Map pipelineParams) {
    String category = pipelineParams.category
    String repoUrl = pipelineParams.repoUrl
    String branch = pipelineParams.branch

    if (!category) { error "category parameter is required" }
    if (!branch?.trim()) { error "branch parameter is required" }
    if (!repoUrl) { error "repoUrl parameter is required" }

    String slackTimestamp = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))

    node('built-in') {
        // Lightweight checkout — just to read build-config.yml
        dir('config-repo') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${branch}"]],
                userRemoteConfigs: [[url: repoUrl, credentialsId: 'git_read_token']],
                extensions: []
            ])
        }

        def yaml = readYaml file: 'config-repo/build/build-config.yml'
        List<JobConfig> allJobs = ConfigParser.populateConfigs(yaml.config, env)
        String prefix = "builds/upyog/${category}/"
        List<JobConfig> categoryJobs = allJobs.findAll { it.getName().startsWith(prefix) }

        if (categoryJobs.isEmpty()) {
            error "No services found for category '${category}'"
        }

        echo "Found ${categoryJobs.size()} services in category '${category}':"
        categoryJobs.each { echo "  - ${it.getName()}" }

        int total = categoryJobs.size()
        int index = 0
        int passed = 0
        int failed = 0
        List<String> failedServices = []
        List<String> passedServices = []

        // Trigger each service job sequentially
        for (JobConfig jobConfig : categoryJobs) {
            String jobName = jobConfig.getName()
            String serviceName = jobName.split('/').last()
            index++
            echo "=== [${index}/${total}] Triggering ${jobName} ==="

            try {
                def buildResult = build(
                    job: jobName,
                    wait: true,
                    propagate: false,
                    parameters: [
                        string(name: 'BRANCH', value: "origin/${branch}"),
                        booleanParam(name: 'ALT_REPO_PUSH', value: false),
                        booleanParam(name: 'WANNA_DEPLOY', value: true)
                    ]
                )

                if (buildResult.result == 'SUCCESS') {
                    passed++
                    passedServices.add(serviceName)
                    echo "  ✅ ${serviceName} - ${buildResult.result}"
                } else {
                    failed++
                    failedServices.add(serviceName)
                    echo "  ❌ ${serviceName} - ${buildResult.result}"
                }

                // Compress build log to save PVC space (Jenkins serves .gz logs natively)
                // Job name "builds/upyog/business-services/billing-service" → path "builds/jobs/upyog/jobs/business-services/jobs/billing-service"
                String logRelPath = jobName.replace("/", "/jobs/")
                String logFile = "/var/jenkins_home/jobs/${logRelPath}/builds/${buildResult.number}/log"
                sh "gzip -f ${logFile} 2>/dev/null || true"

            } catch (Exception err) {
                failed++
                failedServices.add(serviceName)
                echo "  ❌ ${serviceName} - Could not trigger: ${err.message.take(200)}"
            }
        }

        // Build summary
        echo "========================================"
        echo "  CATEGORY BUILD SUMMARY - ${category}"
        echo "========================================"
        echo "  Total:  ${total}"
        echo "  ✅ Passed: ${passed}"
        echo "  ❌ Failed: ${failed}"
        if (failed > 0) {
            echo "  Failed: ${failedServices.join(', ')}"
        }
        echo "========================================"

        // Slack notification
        def color = failed > 0 ? (passed > 0 ? 'warning' : 'danger') : 'good'
        def headerText = failed > 0 ? "⚠️ ${category} — ${passed}/${total} Passed" : "✅ ${category} — All ${total} Passed"

        def slackBlocks = [
            [type: 'header', text: [type: 'plain_text', text: headerText]],
            [type: 'section', fields: [
                [type: 'mrkdwn', text: "*✅ Passed:* ${passed}"],
                [type: 'mrkdwn', text: "*❌ Failed:* ${failed}"]
            ]]
        ]
        if (passedServices) {
            slackBlocks.add([type: 'section', text: [type: 'mrkdwn', text: "*Passed:*\n${passedServices.join(', ')}"]])
        }
        if (failedServices) {
            slackBlocks.add([type: 'section', text: [type: 'mrkdwn', text: "*Failed:*\n${failedServices.join(', ')}"]])
        }
        slackBlocks.add([type: 'context', elements: [
            [type: 'mrkdwn', text: "<${env.BUILD_URL}|View Build>  🕐 ${slackTimestamp} UTC"]
        ]])

        def payload = groovy.json.JsonOutput.toJson([attachments: [[color: color, blocks: slackBlocks]]])
        writeFile file: 'slack-payload.json', text: payload
        sh "curl -s -X POST -H 'Content-type: application/json' --data @slack-payload.json \${SLACK_WEBHOOK} || true"

        // Clean workspace from controller PVC
        deleteDir()

        if (failed > 0) {
            error "${failed} service(s) failed: ${failedServices.join(', ')}"
        }
    }
}
