import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.BuildConfig
import org.egov.jenkins.models.JobConfig

import static org.egov.jenkins.ConfigParser.getCommonBasePath

library 'ci-libs'

def call(Map pipelineParams) {
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
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /kaniko/.docker
      - name: kaniko-cache
        mountPath: /cache            
    resources:
      requests:
        memory: "1792Mi"
        cpu: "750m"
      limits:
        memory: "3954Mi"
        cpu: "1500m"      
  - name: git
    image: docker.io/egovio/builder:2-64da60a1-version_script_update-NA
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true        
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

            def scmVars = checkout scm
            String REPO_NAME = env.REPO_NAME ? env.REPO_NAME : "docker.io/nudmcdg";         
            String GCR_REPO_NAME = "asia.gcr.io/digit-egov";
            def yaml = readYaml file: pipelineParams.configFile;
            List<JobConfig> jobConfigs = ConfigParser.parseConfig(yaml, env);
            String serviceCategory = null;
            String buildNum = null;

            for(int i=0; i<jobConfigs.size(); i++){
                JobConfig jobConfig = jobConfigs.get(i)

                stage('Parse Latest Git Commit') {
                    withEnv(["BUILD_PATH=${jobConfig.getBuildConfigs().get(0).getWorkDir()}",
                             "PATH=alpine:$PATH"
                    ]) {
                        container(name: 'git', shell: '/bin/sh') {
                             // Debug: Check Java and Maven versions in the container
                             sh 'echo "==== JAVA VERSION ===="'
                             sh 'java -version || echo "Java not found"'
                             sh 'echo "==== MAVEN VERSION ===="'
                             sh 'mvn -v || echo "Maven not found"'

                            // Existing logic
                        scmVars['VERSION'] = sh (script:
                               '/scripts/get_application_version.sh ${BUILD_PATH}',
                               returnStdout: true).trim()
                        scmVars['ACTUAL_COMMIT'] = sh (script:
                               '/scripts/get_folder_commit.sh ${BUILD_PATH}',
                               returnStdout: true).trim()
                        scmVars['BRANCH'] = scmVars['GIT_BRANCH'].replaceFirst("origin/", "")
                        }
                    }
                }
                String builtImage = null
                stage('Build with Kaniko') {
                    withEnv(["PATH=/busybox:/kaniko:$PATH"
                    ]) {
                        container(name: 'kaniko', shell: '/busybox/sh') {
                            for(int j=0; j<jobConfig.getBuildConfigs().size(); j++){
                                BuildConfig buildConfig = jobConfig.getBuildConfigs().get(j)
                                echo "${buildConfig.getWorkDir()} ${buildConfig.getDockerFile()}"
                                if( ! fileExists(buildConfig.getWorkDir()) || ! fileExists(buildConfig.getDockerFile()))
                                    throw new Exception("Working directory / dockerfile does not exist!");

                                String workDir = buildConfig.getWorkDir().replaceFirst(getCommonBasePath(buildConfig.getWorkDir(), buildConfig.getDockerFile()), "./")
                                String image = null;
                                if(scmVars.BRANCH.equalsIgnoreCase("master")) {
                                  image = "${REPO_NAME}/${buildConfig.getImageName()}:v${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}";
                                } else {
                                  image = "${REPO_NAME}/${buildConfig.getImageName()}:${scmVars.BRANCH}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}";
                                }
                                builtImage = image 
                                serviceCategory = buildConfig.getServiceCategoryName();  // Dashboard
                                buildNum = "${scmVars.VERSION}"; // Dashboard
                                String noPushImage = env.NO_PUSH ? env.NO_PUSH : false;
                                echo "ALT_REPO_PUSH ENABLED: ${ALT_REPO_PUSH}"
                                 if(env.ALT_REPO_PUSH.equalsIgnoreCase("true")){
                                  String gcr_image = "${GCR_REPO_NAME}/${buildConfig.getImageName()}:${env.BUILD_NUMBER}-${scmVars.BRANCH}-${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}";
                                  sh """
                                    echo \"Attempting to build image,  ${image}\"
                                    /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} -c `pwd`/${buildConfig.getContext()} \
                                    --build-arg WORK_DIR=${workDir} \
                                    --custom-platform=linux/amd64 \
                                    --build-arg token=\$GIT_ACCESS_TOKEN \
                                    --cache=true --cache-dir=/cache \
                                    --single-snapshot=false \
                                    --snapshotMode=redo \
                                    --destination=${image} \
                                    --destination=${gcr_image} \
                                    --no-push=${noPushImage} \
                                    --cache-repo=nudmcdg/cache/cache
                                  """  
                                  echo "${image} and ${gcr_image} pushed successfully!!"                              
                                }
                                // else{
                                // sh """
                                //     echo "===== DEBUG START ====="
                                //     echo "PWD"
                                //     pwd
                                //     echo "===== CURRENT DIR ====="
                                //     ls -la
                                //     echo "===== BUILD CONTEXT ====="
                                //     ls -la ${buildConfig.getContext()}
                                //     echo "DOCKERFILE=${buildConfig.getDockerFile()}"
                                //     echo "CONTEXT=${buildConfig.getContext()}"
                                //     echo "WORKDIR=${workDir}"
                                //     echo "${buildConfig}"
                                //     echo "===== MIGRATION DIR ====="
                                //     ls -la ${buildConfig.getContext()}/migration
                                //     echo "===== MIGRATION MAIN ====="
                                //     ls -la ${buildConfig.getContext()}/migration/main
                                //     echo "===== MIGRATE SH ====="
                                //     ls -la ${buildConfig.getContext()}/migrate.sh
                                //     echo "===== DOCKERFILE ====="
                                //     cat ${buildConfig.getDockerFile()}
                                //     echo "===== DEBUG END ====="
                                // """
                                // sh """
                                //     echo \"Attempting to build image,  ${image}\"
                                //     /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} -c `pwd`/${buildConfig.getContext()} \
                                //     --build-arg WORK_DIR=${workDir} \
                                //     --build-arg token=\$GIT_ACCESS_TOKEN \
                                //     --cache=true --cache-dir=/cache \
                                //     --single-snapshot=true \
                                //     --snapshotMode=time \
                                //     --destination=${image} \
                                //     --no-push=${noPushImage} \
                                //     --cache-repo=nudmcdg/cache/cache
                                // """
                                // echo "${image} pushed successfully!"
                                // }  
                                else{
                                  sh """
                                  echo "===== FILE CHECK ====="
                                  echo "CONTEXT = ${buildConfig.getContext()}"
                                  find ${buildConfig.getContext()} -type f | grep migrate.sh || true
                                  find ${buildConfig.getContext()} -type d | grep migration || true
                                  echo "===== MIGRATE.SH ====="
                                  ls -la ${buildConfig.getContext()}/migrate.sh || true
                                  echo "===== MIGRATION MAIN ====="
                                  ls -la ${buildConfig.getContext()}/migration/main || true
                                  echo "===== DOCKERFILE ====="
                                  cat ${buildConfig.getDockerFile()}
                              """
                              sh """
                                  echo \"Attempting to build image,  ${image}\"
                                  /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} -c `pwd`/${buildConfig.getContext()} \
                                  --build-arg WORK_DIR=${workDir} \
                                  --custom-platform=linux/amd64 \
                                  --build-arg token=\$GIT_ACCESS_TOKEN \
                                  --cache=true --cache-dir=/cache \
                                  --single-snapshot=false \
                                  --snapshotMode=redo \
                                  --destination=${image} \
                                  --no-push=${noPushImage} \
                              """
                              echo "${image} pushed successfully!"
                              // echo "WANNA_DEPLOY = ${env.WANNA_DEPLOY}"
                              }                              
                            }
                        }
                    }
                }
                stage('Deploy') {
                   if(env.WANNA_DEPLOY?.toBoolean()) {
                        if(!builtImage?.trim()) {
                            error("WANNA_DEPLOY=true but builtImage is empty")
                        }
                        echo "Deploying image: ${builtImage}"
                        build(
                            job: "deployments/deploy-to-qa",
                            wait: false,
                            parameters: [
                                string(
                                    name: "IMAGES",
                                    value: builtImage
                                )
                            ]
                        )
                    } else {
                        echo "Deployment skipped. WANNA_DEPLOY was not selected."
                    }
                }
                // stage ("Update dashboard") {
                //         environmentDashboard {
                //             environmentName(scmVars.BRANCH)  
                //             componentName(serviceCategory)
                //             buildNumber(buildNum)
                //             //buildJob(String buildJob)
                //             //packageName(String packageName)
                //             //addColumns(true)
                //             //Date now = new Date()                                
                //             //columns(String Date, now.format("yyMMdd.HHmm", TimeZone.getTimeZone('UTC'))) 
                //     }    
                // }    
            }
        }
    }

}
