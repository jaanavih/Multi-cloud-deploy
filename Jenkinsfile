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
        booleanParam(
            name: 'ENABLE_AI_ANALYSIS',
            defaultValue: true,
            description: 'Enable AI-powered failure analysis using Gemini API'
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
    node {
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
                        
                        // Get additional context for AI analysis
                        def awsContext = ""
                        try {
                            awsContext = sh(
                                script: """
                                echo "=== AWS IDENTITY ==="
                                aws sts get-caller-identity 2>/dev/null || echo "AWS authentication failed"
                                echo "=== CLUSTER STATUS ==="
                                aws eks describe-cluster --region ap-southeast-1 --name hello-cluster 2>/dev/null || echo "Cluster access failed"
                                """,
                                returnStdout: true
                            ).trim()
                        } catch (Exception ex) {
                            awsContext = "AWS context unavailable: ${ex.getMessage()}"
                        }
                        
                        // Try AI solution if enabled
                        if (params.ENABLE_AI_ANALYSIS) {
                            try {
                                echo "\n🤖 Getting AWS troubleshooting advice from Gemini AI..."
                                def aiSolution = getAISolution(e.getMessage(), awsContext)
                                echo "🧠 GEMINI AI SOLUTION:\n${aiSolution}"
                            } catch (Exception aiError) {
                                echo "⚠️ AI analysis failed: ${aiError.getMessage()}"
                            }
                        }
                        
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
                        
                        // Get additional context for AI analysis
                        def gcpContext = ""
                        try {
                            gcpContext = sh(
                                script: """
                                echo "=== GCP AUTHENTICATION ==="
                                gcloud auth list 2>/dev/null || echo "GCP authentication failed"
                                echo "=== PROJECT CONFIG ==="
                                gcloud config get-value project 2>/dev/null || echo "Project configuration failed"
                                echo "=== CLUSTER STATUS ==="
                                gcloud container clusters describe gke-qa2-sg1 --zone asia-southeast1 2>/dev/null || echo "Cluster access failed"
                                """,
                                returnStdout: true
                            ).trim()
                        } catch (Exception ex) {
                            gcpContext = "GCP context unavailable: ${ex.getMessage()}"
                        }
                        
                        // Try AI solution if enabled
                        if (params.ENABLE_AI_ANALYSIS) {
                            try {
                                echo "\n🤖 Getting GCP troubleshooting advice from Gemini AI..."
                                def aiSolution = getAISolution(e.getMessage(), gcpContext)
                                echo "🧠 GEMINI AI SOLUTION:\n${aiSolution}"
                            } catch (Exception aiError) {
                                echo "⚠️ AI analysis failed: ${aiError.getMessage()}"
                            }
                        }
                        
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
                                // Pre-deployment validation
                                sh """
                                echo "🔍 Pre-deployment validation..."
                                echo "Checking if namespace '${params.NAMESPACE}' exists..."
                                kubectl get namespace ${params.NAMESPACE} || {
                                    echo "⚠️  Namespace '${params.NAMESPACE}' not found!"
                                    echo "💡 Available namespaces:"
                                    kubectl get namespaces
                                    echo "🔧 Creating namespace '${params.NAMESPACE}'..."
                                    kubectl create namespace ${params.NAMESPACE}
                                    echo "✅ Namespace '${params.NAMESPACE}' created successfully"
                                }
                                """
                                
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
                                
                                // Comprehensive failure analysis
                                echo """
╔════════════════════════════════════════════════════════════════════════════════╗
║                          🚨 BUILD FAILURE ANALYSIS 🚨                          ║
╚════════════════════════════════════════════════════════════════════════════════╝

🚨 DEPLOYMENT FAILED in stage: ${env.BUILD_STAGE}
📋 Error Message: ${e.getMessage()}

🔍 FAILURE ANALYSIS:
   Build Status: FAILURE  
   Failed Stage: Deploy Application
   Namespace: ${params.NAMESPACE}
   Target Cloud: ${env.TARGET_CLOUD}
   Build Number: #${env.BUILD_NUMBER}"""

                                // Get additional context for AI analysis
                                def additionalContext = ""
                                try {
                                    additionalContext = sh(
                                        script: """
                                        echo "=== CLUSTER INFO ==="
                                        kubectl cluster-info 2>/dev/null || echo "Cluster info unavailable"
                                        echo "=== NAMESPACES ==="
                                        kubectl get namespaces 2>/dev/null || echo "Cannot list namespaces"
                                        echo "=== EVENTS ==="
                                        kubectl get events -n ${params.NAMESPACE} --sort-by='.lastTimestamp' 2>/dev/null | tail -5 || echo "No events found"
                                        """,
                                        returnStdout: true
                                    ).trim()
                                } catch (Exception ex) {
                                    additionalContext = "Context unavailable: ${ex.getMessage()}"
                                }

                                // Try to get AI-powered solution from Gemini
                                if (params.ENABLE_AI_ANALYSIS) {
                                    echo "\n🤖 GETTING AI-POWERED SOLUTION FROM GEMINI..."
                                    try {
                                        def aiSolution = getAISolution(e.getMessage(), additionalContext)
                                        echo """
🧠 GEMINI AI ANALYSIS:
${aiSolution}"""
                                    } catch (Exception aiError) {
                                        echo """
⚠️ AI Analysis Failed: ${aiError.getMessage()}
🔄 Falling back to standard error analysis..."""
                                        
                                        // Fallback to pattern matching
                                        def errorMsg = e.getMessage().toLowerCase()
                                        echo "\n📊 DETECTED ISSUES:"
                                        if (errorMsg.contains('namespace') && errorMsg.contains('not found')) {
                                            echo "   • Namespace Not Found - Target namespace '${params.NAMESPACE}' doesn't exist"
                                        } else if (errorMsg.contains('unauthorized') || errorMsg.contains('forbidden')) {
                                            echo "   • Permission Denied - Insufficient RBAC permissions"
                                        } else if (errorMsg.contains('connection refused') || errorMsg.contains('timeout')) {
                                            echo "   • Network/Connection Issue - Cannot reach Kubernetes API"
                                        } else if (errorMsg.contains('image') && errorMsg.contains('pull')) {
                                            echo "   • Image Pull Error - Container image not accessible"
                                        } else {
                                            echo "   • General Deployment Error - Check logs above for details"
                                        }
                                    }
                                } else {
                                    echo """

ℹ️  AI analysis disabled. Enable 'ENABLE_AI_ANALYSIS' and add 'gemini-api-key' to Jenkins credentials for smart failure analysis.
🔄 Using standard pattern matching analysis..."""
                                    
                                    // Standard pattern matching
                                    def errorMsg = e.getMessage().toLowerCase()
                                    echo "\n📊 DETECTED ISSUES:"
                                    if (errorMsg.contains('namespace') && errorMsg.contains('not found')) {
                                        echo "   • Namespace Not Found - Target namespace '${params.NAMESPACE}' doesn't exist"
                                        echo """
💡 IMMEDIATE FIX:
   1. Create the namespace: kubectl create namespace ${params.NAMESPACE}
   2. Or use existing namespace (e.g., 'default')
   3. Verify namespace exists: kubectl get namespaces"""
                                    } else if (errorMsg.contains('unauthorized') || errorMsg.contains('forbidden')) {
                                        echo "   • Permission Denied - Insufficient RBAC permissions"
                                        echo """
💡 IMMEDIATE FIX:
   1. Check service account permissions
   2. Verify RBAC policies allow deployment
   3. Ensure correct credentials are configured"""
                                    } else if (errorMsg.contains('connection refused') || errorMsg.contains('timeout')) {
                                        echo "   • Network/Connection Issue - Cannot reach Kubernetes API"
                                        echo """
💡 IMMEDIATE FIX:
   1. Check network connectivity to cluster
   2. Verify cluster endpoint is accessible
   3. Check firewall/security group settings"""
                                    } else {
                                        echo "   • General Deployment Error - Check logs above for details"
                                        echo """
💡 GENERAL TROUBLESHOOTING:
   1. Check the full build log for detailed error messages
   2. Verify all prerequisites are met
   3. Test kubectl commands manually"""
                                    }
                                }
                                
                                echo """

🔧 TROUBLESHOOTING COMMANDS:
   kubectl get namespaces                    # List all namespaces
   kubectl create namespace ${params.NAMESPACE}     # Create missing namespace
   kubectl get events -n ${params.NAMESPACE} --sort-by='.lastTimestamp'  # Check events

🔗 BUILD DETAILS:
   Build URL: ${env.BUILD_URL}
   Workspace: ${env.WORKSPACE}
   
════════════════════════════════════════════════════════════════════════════════"""
                                
                                // Get additional debugging info
                                sh """
                                echo "🔍 Additional debugging information..."
                                echo "Available namespaces:"
                                kubectl get namespaces || echo "Cannot list namespaces"
                                echo "Current kubectl context:"
                                kubectl config current-context || echo "No context available"
                                echo "Cluster info:"
                                kubectl cluster-info || echo "Cannot get cluster info"
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
                                // Pre-deployment validation
                                sh """
                                echo "🔍 Pre-deployment validation..."
                                echo "Checking if namespace '${params.NAMESPACE}' exists..."
                                kubectl get namespace ${params.NAMESPACE} || {
                                    echo "⚠️  Namespace '${params.NAMESPACE}' not found!"
                                    echo "💡 Available namespaces:"
                                    kubectl get namespaces
                                    echo "🔧 Creating namespace '${params.NAMESPACE}'..."
                                    kubectl create namespace ${params.NAMESPACE}
                                    echo "✅ Namespace '${params.NAMESPACE}' created successfully"
                                }
                                """
                                
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
                                
                                // Comprehensive failure analysis
                                echo """
╔════════════════════════════════════════════════════════════════════════════════╗
║                          🚨 BUILD FAILURE ANALYSIS 🚨                          ║
╚════════════════════════════════════════════════════════════════════════════════╝

🚨 DEPLOYMENT FAILED in stage: ${env.BUILD_STAGE}
📋 Error Message: ${e.getMessage()}

🔍 FAILURE ANALYSIS:
   Build Status: FAILURE  
   Failed Stage: Deploy Application
   Namespace: ${params.NAMESPACE}
   Target Cloud: ${env.TARGET_CLOUD}
   Build Number: #${env.BUILD_NUMBER}"""

                                // Get additional context for AI analysis
                                def additionalContext = ""
                                try {
                                    additionalContext = sh(
                                        script: """
                                        echo "=== CLUSTER INFO ==="
                                        kubectl cluster-info 2>/dev/null || echo "Cluster info unavailable"
                                        echo "=== NAMESPACES ==="
                                        kubectl get namespaces 2>/dev/null || echo "Cannot list namespaces"
                                        echo "=== EVENTS ==="
                                        kubectl get events -n ${params.NAMESPACE} --sort-by='.lastTimestamp' 2>/dev/null | tail -5 || echo "No events found"
                                        """,
                                        returnStdout: true
                                    ).trim()
                                } catch (Exception ex) {
                                    additionalContext = "Context unavailable: ${ex.getMessage()}"
                                }

                                // Try to get AI-powered solution from Gemini
                                if (params.ENABLE_AI_ANALYSIS) {
                                    echo "\n🤖 GETTING AI-POWERED SOLUTION FROM GEMINI..."
                                    try {
                                        def aiSolution = getAISolution(e.getMessage(), additionalContext)
                                        echo """
🧠 GEMINI AI ANALYSIS:
${aiSolution}"""
                                    } catch (Exception aiError) {
                                        echo """
⚠️ AI Analysis Failed: ${aiError.getMessage()}
🔄 Falling back to standard error analysis..."""
                                        
                                        // Fallback to pattern matching
                                        def errorMsg = e.getMessage().toLowerCase()
                                        echo "\n📊 DETECTED ISSUES:"
                                        if (errorMsg.contains('namespace') && errorMsg.contains('not found')) {
                                            echo "   • Namespace Not Found - Target namespace '${params.NAMESPACE}' doesn't exist"
                                        } else if (errorMsg.contains('unauthorized') || errorMsg.contains('forbidden')) {
                                            echo "   • Permission Denied - Insufficient RBAC permissions"
                                        } else if (errorMsg.contains('connection refused') || errorMsg.contains('timeout')) {
                                            echo "   • Network/Connection Issue - Cannot reach Kubernetes API"
                                        } else if (errorMsg.contains('image') && errorMsg.contains('pull')) {
                                            echo "   • Image Pull Error - Container image not accessible"
                                        } else {
                                            echo "   • General Deployment Error - Check logs above for details"
                                        }
                                    }
                                } else {
                                    echo """

ℹ️  AI analysis disabled. Enable 'ENABLE_AI_ANALYSIS' and add 'gemini-api-key' to Jenkins credentials for smart failure analysis.
🔄 Using standard pattern matching analysis..."""
                                    
                                    // Standard pattern matching
                                    def errorMsg = e.getMessage().toLowerCase()
                                    echo "\n📊 DETECTED ISSUES:"
                                    if (errorMsg.contains('namespace') && errorMsg.contains('not found')) {
                                        echo "   • Namespace Not Found - Target namespace '${params.NAMESPACE}' doesn't exist"
                                        echo """
💡 IMMEDIATE FIX:
   1. Create the namespace: kubectl create namespace ${params.NAMESPACE}
   2. Or use existing namespace (e.g., 'default')
   3. Verify namespace exists: kubectl get namespaces"""
                                    } else if (errorMsg.contains('unauthorized') || errorMsg.contains('forbidden')) {
                                        echo "   • Permission Denied - Insufficient RBAC permissions"
                                        echo """
💡 IMMEDIATE FIX:
   1. Check service account permissions
   2. Verify RBAC policies allow deployment
   3. Ensure correct credentials are configured"""
                                    } else if (errorMsg.contains('connection refused') || errorMsg.contains('timeout')) {
                                        echo "   • Network/Connection Issue - Cannot reach Kubernetes API"
                                        echo """
💡 IMMEDIATE FIX:
   1. Check network connectivity to cluster
   2. Verify cluster endpoint is accessible
   3. Check firewall/security group settings"""
                                    } else {
                                        echo "   • General Deployment Error - Check logs above for details"
                                        echo """
💡 GENERAL TROUBLESHOOTING:
   1. Check the full build log for detailed error messages
   2. Verify all prerequisites are met
   3. Test kubectl commands manually"""
                                    }
                                }
                                
                                echo """

🔧 TROUBLESHOOTING COMMANDS:
   kubectl get namespaces                    # List all namespaces
   kubectl create namespace ${params.NAMESPACE}     # Create missing namespace
   kubectl get events -n ${params.NAMESPACE} --sort-by='.lastTimestamp'  # Check events

🔗 BUILD DETAILS:
   Build URL: ${env.BUILD_URL}
   Workspace: ${env.WORKSPACE}
   
════════════════════════════════════════════════════════════════════════════════"""
                                
                                // Get additional debugging info
                                sh """
                                echo "🔍 Additional debugging information..."
                                echo "Available namespaces:"
                                kubectl get namespaces || echo "Cannot list namespaces"
                                echo "Current kubectl context:"
                                kubectl config current-context || echo "No context available"
                                echo "Cluster info:"
                                kubectl cluster-info || echo "Cannot get cluster info"
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
    } // end of node block
} // end of podTemplate block

// Functions are defined outside the podTemplate block

// Function to safely call Gemini API with credentials
def getAISolution(String errorMessage, String context) {
    if (!params.ENABLE_AI_ANALYSIS) {
        return "AI analysis disabled"
    }
    
    try {
        withCredentials([string(credentialsId: 'gemini-api-key', variable: 'GEMINI_API_KEY')]) {
            return getGeminiSolution(errorMessage, context, env.GEMINI_API_KEY)
        }
    } catch (Exception e) {
        throw new Exception("Credentials 'gemini-api-key' not found. Please add your Gemini API key to Jenkins credentials with ID 'gemini-api-key'")
    }
}

// Function to get AI-powered solution from Gemini
def getGeminiSolution(String errorMessage, String context, String apiKey) {
    def prompt = """
You are a Kubernetes deployment expert. Analyze this Jenkins pipeline failure and provide a concise solution.

ERROR MESSAGE:
${errorMessage}

ADDITIONAL CONTEXT:
${context}

DEPLOYMENT INFO:
- Cloud: ${env.TARGET_CLOUD ?: 'Unknown'}
- Namespace: ${params.NAMESPACE}
- Action: ${params.ACTION}
- Stage: Deploy Application

Please provide:
1. Root Cause (1-2 sentences)
2. Immediate Fix (2-3 specific commands)
3. Prevention (1 tip to avoid this in future)

Keep response under 200 words and focus on actionable solutions.
"""

    def response = sh(
        script: """
        curl -s -X POST "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=${apiKey}" \\
        -H "Content-Type: application/json" \\
        -d '{
            "contents": [{
                "parts": [{
                    "text": "${prompt.replace('"', '\\"').replace('\n', '\\n')}"
                }]
            }],
            "generationConfig": {
                "maxOutputTokens": 300,
                "temperature": 0.1
            }
        }' | jq -r '.candidates[0].content.parts[0].text // "AI analysis unavailable"'
        """,
        returnStdout: true
    ).trim()

    if (response && response != "AI analysis unavailable" && response != "null") {
        return response
    } else {
        throw new Exception("Gemini API returned empty or invalid response")
    }
}
