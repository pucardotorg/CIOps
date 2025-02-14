import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.BuildConfig
import org.egov.jenkins.models.JobConfig

import static org.egov.jenkins.ConfigParser.getCommonBasePath

library 'ci-libs'

def call(Map pipelineParams) {
    podTemplate(yaml: """
kind: Pod
metadata:
  namespace: backbone-prod
  name: kaniko
spec:
  imagePullSecrets:
    - name: acr-secret
  containers:
  - name: kaniko
    image: pucarsolutionsdev.azurecr.io/executor:debug
    imagePullPolicy: IfNotPresent
    command:
    - /busybox/cat
    tty: true
    env:
      - name: GIT_ACCESS_TOKEN
        value: demo
      - name: token
        value: demo             
      - name: "GOOGLE_APPLICATION_CREDENTIALS"
        value: "/var/run/secret/cloud.google.com/service-account.json"
      - name: NEXUS_USERNAME
        value: demo                    
      - name: NEXUS_PASSWORD
        value: demo                       
      - name: CI_DB_USER
        value: demo                       
      - name: CI_DB_PWD
        value: demo                    
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /root/.docker
      - name: kaniko-cache
        mountPath: /cache  
      - name: service-account
        mountPath: /var/run/secret/cloud.google.com        
    resources:
      requests:
        memory: "1792Mi"
        cpu: "750m"
      limits:
        memory: "4080Mi"
        cpu: "1500m"   
//  - name: jnlp
//    image: pucarsolutionsdev.azurecr.io/inbound-agent:3283.v92c105e0f819-4
//    imagePullPolicy: IfNotPresent
//    env:
//      - name: JENKINS_TUNNEL
//        value: jenkins-agent.backbone-prod.svc.cluster.local:50000
//      - name: JENKINS_AGENT_WORKDIR
//        value: /home/jenkins/agent
//      - name: JENKINS_URL
//        value: http://jenkins.backbone-prod.svc.cluster.local:8080/jenkins
//    resources:
//      requests:
//        cpu: 100m
//        memory: 256Mi
//    volumeMounts:
//      - mountPath: /home/jenkins/agent
//        name: workspace
  - name: git
    image: pucarsolutionsdev.azurecr.io/builder:2-64da60a1-version_script_update-NA
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true        
  volumes:
  - name: kaniko-cache
    persistentVolumeClaim:
      claimName: kaniko-cache-claim
      readOnly: true  
  - emptyDir: {}
    name: workspace    
  - name: service-account
    vaule: backbone-prod-sa  
  - name: jenkins-docker-cfg
    value: demo   
  serviceAccount: backbone-prod-sa
  serviceAccountName: backbone-prod-sa
"""
    ) {
        node(POD_LABEL) {

            def scmVars = checkout scm
            String REPO_NAME = env.REPO_NAME ? env.REPO_NAME : "registry.backbone-prod:5000";
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
                                    --build-arg token=\$GIT_ACCESS_TOKEN \
                                    --build-arg nexusUsername=\$NEXUS_USERNAME \
                                    --build-arg nexusPassword=\$NEXUS_PASSWORD \
                                    --build-arg ciDbUsername=\$CI_DB_USER \
                                    --build-arg ciDbpassword=\$CI_DB_PWD \
                                    --cache=true --cache-dir=/cache \
                                    --single-snapshot=true \
                                    --snapshotMode=time \
                                    --destination=${image} \
                                    --destination=${gcr_image} \
                                    --no-push=${noPushImage} \
                                    --cache-repo=egovio/cache/cache
                                  """  
                                  echo "${image} and ${gcr_image} pushed successfully!!"                              
                                }
                                else{
                                sh """
                                    echo \"Attempting to build image,  ${image}\"
                                    /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} -c `pwd`/${buildConfig.getContext()} \
                                    --build-arg WORK_DIR=${workDir} \
                                    --build-arg token=\$GIT_ACCESS_TOKEN \
                                    --build-arg nexusUsername=\$NEXUS_USERNAME \
                                    --build-arg nexusPassword=\$NEXUS_PASSWORD \
                                    --build-arg ciDbUsername=\$CI_DB_USER \
                                    --build-arg ciDbpassword=\$CI_DB_PWD \
                                    --cache=true --cache-dir=/cache \
                                    --single-snapshot=true \
                                    --snapshotMode=time \
                                    --destination=${image} \
                                    --no-push=${noPushImage} \
                                    --cache-repo=egovio/cache/cache
                                """
                                echo "${image} pushed successfully!"
                                }                                
                            }
                        }
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
