/**
 * Default pipeline (Option A): cost + optional AI reports, then input() to choose AWS/GCP,
 * then install → kubeconfig → deploy/delete (same build).
 *
 * Legacy alternative (pick cloud on first parameter screen): Jenkinsfile.parameters-first
 *
 * Cost logic is loaded from lib/costComparison.groovy after checkout (no Global Pipeline Library).
 *
 * Jenkins cannot show HTML before the first parameter screen; review reports after the
 * build starts, then approve the input step.
 */
properties([
    parameters([
        booleanParam(
            name: 'SHOW_COST_COMPARISON',
            defaultValue: true,
            description: 'Run deterministic cost comparison + HTML reports before choosing cloud'
        ),
        string(
            name: 'NAMESPACE',
            defaultValue: 'default',
            description: 'Kubernetes namespace'
        ),
        choice(
            name: 'ACTION',
            choices: ['deploy', 'delete'],
            description: 'Choose action (applied after you pick the cloud)'
        )
    ])
])

podTemplate(
    yaml: '''
apiVersion: v1
kind: Pod
spec:
  tolerations:
    - key: "role"
      operator: "Exists"
      effect: "NoSchedule"
    - key: "CriticalAddonsOnly"
      operator: "Exists"
  containers:
    - name: tools
      image: google/cloud-sdk:latest
      command:
        - sleep
      args:
        - "999999"
      tty: true
'''
) {

node(POD_LABEL) {

    stage('Checkout') {
        checkout scm
    }

    stage('💰 Cost comparison (+ optional AI)') {
        container('tools') {
            script {
                if (params.SHOW_COST_COMPARISON != true) {
                    echo 'Skipping cost comparison (SHOW_COST_COMPARISON is false)'
                } else {
                    try {
                        echo '🔍 Analyzing deployment costs using real-time public APIs...'
                        
                        // Install jq for JSON parsing (required for public API calls)
                        sh '''
                        echo "🛠️ Installing jq for pricing API calls..."
                        apt-get update -qq
                        apt-get install -y -qq jq curl
                        jq --version
                        '''
                        
                        def costConfig = [
                            awsRegion: 'ap-southeast-1',
                            gcpRegion: 'asia-southeast1',
                            hoursPerMonth: 730
                        ]
                        def costLib = load 'lib/costComparison.groovy'
                        def costResults = costLib.runCostComparison(costConfig)
                        
                        // Summary for pipeline description (use local variables to avoid conflict)
                        def pipelineCheaperProvider = costResults.aws.total > costResults.gcp.total ? 'GCP' : 'AWS'
                        def pipelineSavings = Math.abs((costResults.aws.total - costResults.gcp.total) as double)
                        def pipelineSavingsPercent = Math.abs(((costResults.aws.total - costResults.gcp.total) / Math.max(costResults.aws.total, costResults.gcp.total) * 100) as double)
                        
                        echo """
🎯 COST ANALYSIS COMPLETE! 
   ${pipelineCheaperProvider} is ${String.format("%.1f", pipelineSavingsPercent)}% cheaper (\$${String.format("%.2f", pipelineSavings)}/month savings)
   📊 See detailed breakdown above ⬆️
   📋 Review HTML report after build starts ➡️
                        """
                        publishHTML([
                            allowMissing: false,
                            alwaysLinkToLastBuild: true,
                            keepAll: true,
                            reportDir: '.',
                            reportFiles: 'cost-comparison-report.html',
                            reportName: '💰 Cost Comparison Report',
                            reportTitles: 'Multi-Cloud Cost Analysis'
                        ])
                        // Store cost results in build description instead of env var (writeJSON not available)
                        currentBuild.description = "💰 ${pipelineCheaperProvider} cheaper by \$${String.format("%.2f", pipelineSavings)}/month (pick cloud next)"
                    } catch (Exception e) {
                        echo "⚠️  Cost comparison failed: ${e.message}"
                    }
                }
            }
        }
    }

    stage('✋ Choose cloud (after reviewing reports)') {
        script {
            def raw = input(
                message: 'Review the published HTML reports on this build, then choose AWS or GCP.',
                ok: 'Continue',
                parameters: [
                    choice(
                        name: 'CLOUD_PROVIDER',
                        choices: ['aws', 'gcp'],
                        description: 'Target cluster for kubectl'
                    )
                ]
            )
            if (raw instanceof Map) {
                env.TARGET_CLOUD = raw['CLOUD_PROVIDER']
            } else {
                env.TARGET_CLOUD = raw.toString()
            }
            echo "Selected cloud: ${env.TARGET_CLOUD}"
        }
    }

    // Downstream deploy job option removed for cleaner demo UI

    stage('Install Tools') {
        container('tools') {
            script {
                sh '''
                apt-get update
                apt-get install -y curl unzip git
                curl -LO "https://dl.k8s.io/release/v1.30.0/bin/linux/amd64/kubectl"
                chmod +x kubectl
                mv kubectl /usr/local/bin/
                kubectl version --client
                '''
                if (env.TARGET_CLOUD == 'aws') {
                    sh '''
                    curl -s "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
                    unzip -q awscliv2.zip
                    ./aws/install || true
                    aws --version
                    '''
                }
                if (env.TARGET_CLOUD == 'gcp') {
                    sh '''
                    gcloud components install gke-gcloud-auth-plugin -q || true
                    gcloud version
                    '''
                }
            }
        }
    }

    stage('Configure Cluster Access') {
        container('tools') {
            script {
                if (env.TARGET_CLOUD == 'aws') {
                    try {
                        withCredentials([[
                            $class: 'AmazonWebServicesCredentialsBinding',
                            credentialsId: 'aws-creds'
                        ]]) {
                            sh '''
                            echo "🔐 Configuring AWS EKS cluster access..."
                            mkdir -p /root/.kube
                            aws eks update-kubeconfig --region ap-southeast-1 --name hello-cluster
                            echo "✅ Checking cluster connectivity..."
                            kubectl get nodes
                            kubectl cluster-info
                            '''
                        }
                    } catch (Exception e) {
                        env.BUILD_STAGE = 'Configure AWS Cluster Access'
                        echo "🚨 AWS CLUSTER ACCESS FAILED in stage: ${env.BUILD_STAGE}"
                        echo "Error: ${e.getMessage()}"
                        sh """
                        echo "🔍 AWS Cluster Access Debugging..."
                        aws sts get-caller-identity || echo "AWS authentication failed"
                        aws eks describe-cluster --region ap-southeast-1 --name hello-cluster || echo "Cluster access failed"
                        """
                        throw e
                    }
                } else if (env.TARGET_CLOUD == 'gcp') {
                    try {
                        withCredentials([
                            file(credentialsId: 'gcp-sa-key', variable: 'GCP_KEY')
                        ]) {
                            sh '''
                            echo "🔐 Configuring GCP GKE cluster access..."
                            export GOOGLE_APPLICATION_CREDENTIALS=$GCP_KEY
                            gcloud auth activate-service-account --key-file=$GOOGLE_APPLICATION_CREDENTIALS
                            gcloud config set project gke-qa2-36938
                            gcloud container clusters get-credentials gke-qa2-sg1 \
                              --zone asia-southeast1 --project gke-qa2-36938 --internal-ip
                            echo "✅ Checking cluster connectivity..."
                            kubectl get nodes
                            kubectl cluster-info
                            '''
                        }
                    } catch (Exception e) {
                        env.BUILD_STAGE = 'Configure GCP Cluster Access'
                        echo "🚨 GCP CLUSTER ACCESS FAILED in stage: ${env.BUILD_STAGE}"
                        echo "Error: ${e.getMessage()}"
                        sh """
                        echo "🔍 GCP Cluster Access Debugging..."
                        gcloud auth list || echo "GCP authentication failed"
                        gcloud config get-value project || echo "Project configuration failed"
                        gcloud container clusters describe gke-qa2-sg1 --zone asia-southeast1 || echo "Cluster access failed"
                        """
                        throw e
                    }
                }
            }
        }
    }

    stage('Deploy/Delete Application') {
        container('tools') {
            script {
                if (env.TARGET_CLOUD == 'aws') {
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: 'aws-creds'
                    ]]) {
                        if (params.ACTION == 'deploy') {
                            try {
                                sh """
                                echo "🚀 Applying Kubernetes manifests..."
                                
                                # Apply deployments and services (with timestamp for rolling update)
                                echo "🔄 Preparing deployment with timestamp for rolling update..."
                                TIMESTAMP=\$(date +%Y%m%d-%H%M%S)
                                BUILD_NUM=\${BUILD_NUMBER:-\$(date +%s)}
                                sed "s/DEPLOYMENT_TIMESTAMP_PLACEHOLDER/\$TIMESTAMP/g; s/BUILD_ID_PLACEHOLDER/\$BUILD_NUM/g" k8s/deployment.yaml > /tmp/deployment-\$TIMESTAMP.yaml
                                kubectl apply -n ${params.NAMESPACE} -f /tmp/deployment-\$TIMESTAMP.yaml
                                kubectl apply -n ${params.NAMESPACE} -f k8s/service.yaml
                                
                                echo "⏳ Waiting for deployment to be ready (timeout: 10 minutes)..."
                                kubectl rollout status deployment/hello-app -n ${params.NAMESPACE} --timeout=600s
                                
                                echo "📋 Final deployment status:"
                                kubectl get pods,svc -n ${params.NAMESPACE}
                                
                                echo "🔍 Checking application readiness..."
                                kubectl get pods -n ${params.NAMESPACE} -l app=hello-app -o wide
                                
                                echo "🩺 Troubleshooting any issues..."
                                # Check if pods are still not ready after rollout
                                NOT_READY=\$(kubectl get pods -n ${params.NAMESPACE} -l app=hello-app --no-headers | grep -v "1/1.*Running" | wc -l)
                                if [ \$NOT_READY -gt 0 ]; then
                                    echo "⚠️  Found \$NOT_READY pods not ready. Investigating..."
                                    kubectl describe pods -n ${params.NAMESPACE} -l app=hello-app | grep -A 10 "Events:"
                                    kubectl get events -n ${params.NAMESPACE} --sort-by='.lastTimestamp' | tail -10
                                    echo "🚨 DEPLOYMENT ISSUE DETECTED - Some pods are not ready"
                                    exit 1
                                else
                                    echo "✅ All pods are ready and running!"
                                fi
                                """
                            } catch (Exception e) {
                                env.BUILD_STAGE = 'Deploy Application'
                                echo "🚨 DEPLOYMENT FAILED in stage: ${env.BUILD_STAGE}"
                                echo "Error: ${e.getMessage()}"
                                // Get additional debugging info
                                sh """
                                echo "🔍 Debugging deployment failure..."
                                kubectl get events -n ${params.NAMESPACE} --sort-by='.lastTimestamp' | tail -20
                                kubectl describe deployment hello-app -n ${params.NAMESPACE} || echo "Deployment not found"
                                kubectl get pods -n ${params.NAMESPACE} -l app=hello-app || echo "No pods found"
                                """
                                throw e
                            }
                        }
                        if (params.ACTION == 'delete') {
                            sh """
                            kubectl delete -n ${params.NAMESPACE} -f k8s/deployment.yaml || true
                            kubectl delete -n ${params.NAMESPACE} -f k8s/service.yaml || true
                            """
                        }
                    }
                } else if (env.TARGET_CLOUD == 'gcp') {
                    withCredentials([
                        file(credentialsId: 'gcp-sa-key', variable: 'GCP_KEY')
                    ]) {
                        if (params.ACTION == 'deploy') {
                            try {
                                sh """
                                echo "🚀 Applying Kubernetes manifests..."
                                
                                # Apply deployments and services (with timestamp for rolling update)
                                echo "🔄 Preparing deployment with timestamp for rolling update..."
                                TIMESTAMP=\$(date +%Y%m%d-%H%M%S)
                                BUILD_NUM=\${BUILD_NUMBER:-\$(date +%s)}
                                sed "s/DEPLOYMENT_TIMESTAMP_PLACEHOLDER/\$TIMESTAMP/g; s/BUILD_ID_PLACEHOLDER/\$BUILD_NUM/g" k8s/deployment.yaml > /tmp/deployment-\$TIMESTAMP.yaml
                                kubectl apply -n ${params.NAMESPACE} -f /tmp/deployment-\$TIMESTAMP.yaml
                                kubectl apply -n ${params.NAMESPACE} -f k8s/service.yaml
                                
                                echo "⏳ Waiting for deployment to be ready (timeout: 10 minutes)..."
                                kubectl rollout status deployment/hello-app -n ${params.NAMESPACE} --timeout=600s
                                
                                echo "📋 Final deployment status:"
                                kubectl get pods,svc -n ${params.NAMESPACE}
                                
                                echo "🔍 Checking application readiness..."
                                kubectl get pods -n ${params.NAMESPACE} -l app=hello-app -o wide
                                
                                echo "🩺 Troubleshooting any issues..."
                                # Check if pods are still not ready after rollout
                                NOT_READY=\$(kubectl get pods -n ${params.NAMESPACE} -l app=hello-app --no-headers | grep -v "1/1.*Running" | wc -l)
                                if [ \$NOT_READY -gt 0 ]; then
                                    echo "⚠️  Found \$NOT_READY pods not ready. Investigating..."
                                    kubectl describe pods -n ${params.NAMESPACE} -l app=hello-app | grep -A 10 "Events:"
                                    kubectl get events -n ${params.NAMESPACE} --sort-by='.lastTimestamp' | tail -10
                                    echo "🚨 DEPLOYMENT ISSUE DETECTED - Some pods are not ready"
                                    exit 1
                                else
                                    echo "✅ All pods are ready and running!"
                                fi
                                """
                            } catch (Exception e) {
                                env.BUILD_STAGE = 'Deploy Application'
                                echo "🚨 DEPLOYMENT FAILED in stage: ${env.BUILD_STAGE}"
                                echo "Error: ${e.getMessage()}"
                                // Get additional debugging info
                                sh """
                                echo "🔍 Debugging deployment failure..."
                                kubectl get events -n ${params.NAMESPACE} --sort-by='.lastTimestamp' | tail -20
                                kubectl describe deployment hello-app -n ${params.NAMESPACE} || echo "Deployment not found"
                                kubectl get pods -n ${params.NAMESPACE} -l app=hello-app || echo "No pods found"
                                """
                                throw e
                            }
                        }
                        if (params.ACTION == 'delete') {
                            sh """
                            kubectl delete -n ${params.NAMESPACE} -f k8s/deployment.yaml || true
                            kubectl delete -n ${params.NAMESPACE} -f k8s/service.yaml || true
                            """
                        }
                    }
                }
            }
        }
    }
}

// Post-build actions for error handling and failure reporting
post {
    always {
        script {
            if (currentBuild.result == 'FAILURE' || currentBuild.result == 'ABORTED') {
                container('tools') {
                    echo """
╔════════════════════════════════════════════════════════════════════════════════╗
║                          🚨 BUILD FAILURE ANALYSIS 🚨                          ║
╚════════════════════════════════════════════════════════════════════════════════╝
"""
                    
                    // Capture and analyze the build log for failure reasons
                    def failureReasons = []
                    def buildLog = currentBuild.rawBuild.getLog(500) // Get last 500 lines
                    
                    // Common failure patterns to search for
                    def errorPatterns = [
                        ['Authentication Failed', /(?i)(authentication.*failed|unauthorized|access.*denied|invalid.*credentials)/],
                        ['Network/Connection Issue', /(?i)(connection.*refused|network.*unreachable|timeout|dial tcp.*connect)/],
                        ['Kubernetes API Error', /(?i)(error.*server.*could.*not.*find|forbidden|the.*server.*could.*not.*find)/],
                        ['Image Pull Error', /(?i)(imagepullbackoff|errimagepull|pull.*access.*denied)/],
                        ['Resource Not Found', /(?i)(not.*found|does.*not.*exist|configmap.*not.*found)/],
                        ['Namespace Mismatch', /(?i)(namespace.*does.*not.*match|namespace.*mismatch)/],
                        ['Deployment Timeout', /(?i)(rollout.*timeout|deployment.*timeout|waiting.*timeout)/],
                        ['Pod Startup Failure', /(?i)(containercreating.*failed|pod.*has.*unbound.*immediate)/],
                        ['Permission Denied', /(?i)(permission.*denied|forbidden|access.*denied)/],
                        ['Script Error', /(?i)(script.*returned.*exit.*code|command.*not.*found)/]
                    ]
                    
                    buildLog.each { line ->
                        errorPatterns.each { pattern ->
                            if (line =~ pattern[1]) {
                                if (!failureReasons.contains(pattern[0])) {
                                    failureReasons.add(pattern[0])
                                }
                            }
                        }
                    }
                    
                    // Get the last few error lines from the build log
                    def errorLines = buildLog.findAll { line ->
                        line.contains('ERROR:') || line.contains('FAILED') || line.contains('error:') || 
                        line.contains('Exception') || line.contains('+ exit') || line.contains('script returned exit code')
                    }.takeRight(3)
                    
                    echo """
🔍 FAILURE ANALYSIS:
   Build Status: ${currentBuild.result}
   Build Duration: ${currentBuild.durationString}
   Failed Stage: ${env.STAGE_NAME ?: 'Unknown'}
   
📊 DETECTED ISSUES:"""
                    
                    if (failureReasons.isEmpty()) {
                        echo "   • No specific patterns detected - check full build log for details"
                    } else {
                        failureReasons.each { reason ->
                            echo "   • ${reason}"
                        }
                    }
                    
                    if (!errorLines.isEmpty()) {
                        echo """
🚨 RECENT ERROR MESSAGES:"""
                        errorLines.each { line ->
                            echo "   ${line.trim()}"
                        }
                    }
                    
                    echo """
💡 TROUBLESHOOTING STEPS:
   1. Check the full build log above for detailed error messages
   2. Verify credentials and cluster access are properly configured
   3. Ensure the target namespace exists and has proper permissions
   4. Check if cluster nodes have sufficient resources
   5. Validate network connectivity to the Kubernetes API
   
🔗 BUILD DETAILS:
   Build Number: #${env.BUILD_NUMBER}
   Build URL: ${env.BUILD_URL}
   Workspace: ${env.WORKSPACE}

════════════════════════════════════════════════════════════════════════════════"""
                }
            } else {
                echo """
✅ BUILD COMPLETED SUCCESSFULLY! 
   Status: ${currentBuild.result ?: 'SUCCESS'}
   Duration: ${currentBuild.durationString}
   Build #${env.BUILD_NUMBER}
"""
            }
        }
    }
    
    failure {
        script {
            // Send additional notifications or perform cleanup if needed
            echo "🚨 Build failed - failure handlers executed"
        }
    }
    
    success {
        script {
            echo "🎉 Build completed successfully - success handlers executed"
        }
    }
}

}
