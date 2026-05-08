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
 *
 * UPDATED: Fixed brace matching - Build timestamp: 2026-05-08 05:50
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

// AI Analysis Functions - Must be defined before podTemplate block
def getAISolution(String errorMessage, String context) {
    try {
        withCredentials([string(credentialsId: 'gemini-api-key', variable: 'GEMINI_API_KEY')]) {
            return getGeminiSolution(errorMessage, context, env.GEMINI_API_KEY)
        }
    } catch (Exception e) {
        throw new Exception("AI Analysis requires 'gemini-api-key' credential. Add it in Jenkins: Manage Jenkins > Credentials > Add Secret Text with ID 'gemini-api-key'")
    }
}

def getGeminiSolution(String errorMessage, String context, String apiKey) {
    def prompt = "Please give me the fix in one sentence along with command for the issue - ${errorMessage}"

    def response = sh(
        script: """
        # First, try to get available models to find the correct one
        MODELS=\$(curl -s "https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}" | jq -r '.models[].name' | grep -E 'gemini' | head -1)
        echo "DEBUG: Available Gemini model: \$MODELS" >&2
        
        # Use the found model or fallback to gemini-1.5-pro
        MODEL_NAME=\${MODELS:-"models/gemini-1.5-pro"}
        echo "DEBUG: Using model: \$MODEL_NAME" >&2
        
        # Create properly escaped JSON payload
        cat > /tmp/gemini_payload.json << 'EOF'
{
    "contents": [{
        "parts": [{
            "text": "${prompt.replace('"', '\\"').replace('\n', '\\n').replace('\\', '\\\\').replace('\r', '')}"
        }]
    }],
    "generationConfig": {
        "maxOutputTokens": 1000,
        "temperature": 0.1
    }
}
EOF
        
        RESPONSE=\$(curl -s -X POST "https://generativelanguage.googleapis.com/v1beta/\$MODEL_NAME:generateContent?key=${apiKey}" \\
        -H "Content-Type: application/json" \\
        -d @/tmp/gemini_payload.json)
        
        echo "DEBUG: Raw API Response: \$RESPONSE" >&2
        
        AI_TEXT=\$(echo "\$RESPONSE" | jq -r '.candidates[0].content.parts[0].text // "AI analysis unavailable"')
        echo "DEBUG: Extracted AI Text: \$AI_TEXT" >&2
        
        # Clean up temp file
        rm -f /tmp/gemini_payload.json
        
        echo "\$AI_TEXT"
        """,
        returnStdout: true
    ).trim()

    if (response && response != "AI analysis unavailable" && response != "null" && response != "") {
        return response
    } else {
        throw new Exception("Gemini API returned empty or invalid response: ${response}")
    }
}

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
                                if ! kubectl get namespace ${params.NAMESPACE} >/dev/null 2>&1; then
                                    echo "❌ ERROR: Namespace '${params.NAMESPACE}' not found!"
                                    echo "💡 Available namespaces:"
                                    kubectl get namespaces
                                    echo ""
                                    echo "🚨 DEPLOYMENT FAILED: Target namespace does not exist"
                                    echo "📋 TO FIX: Create the namespace first or use an existing one:"
                                    echo "   kubectl create namespace ${params.NAMESPACE}"
                                    echo "   OR use an existing namespace like 'default' or 'test-app'"
                                    # Write specific error for catch block to read
                                    echo "NAMESPACE_NOT_FOUND:${params.NAMESPACE}" > /tmp/deployment_error
                                    exit 1
                                fi
                                echo "✅ Namespace '${params.NAMESPACE}' exists, proceeding with deployment"
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
                                
                                // Store failure info for final summary (verbose analysis suppressed)
                                env.BUILD_FAILED = 'true'
                                
                                // Check for specific error types
                                def specificError = ""
                                def errorReason = e.getMessage()
                                try {
                                    specificError = sh(script: "cat /tmp/deployment_error 2>/dev/null || echo 'GENERIC_ERROR'", returnStdout: true).trim()
                                    if (specificError.startsWith("NAMESPACE_NOT_FOUND:")) {
                                        def missingNamespace = specificError.split(":")[1]
                                        errorReason = "Namespace '${missingNamespace}' does not exist"
                                    }
                                } catch (Exception ex) {
                                    // File doesn't exist, use generic error
                                }
                                env.BUILD_ERROR = errorReason
                                
                                // Clean up error file
                                try {
                                    sh "rm -f /tmp/deployment_error"
                                } catch (Exception ex) {
                                    // Ignore cleanup errors
                                }

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

                                // Get AI-powered solution from Gemini (always enabled)
                                try {
                                    // echo "\n🤖 GETTING AI-POWERED SOLUTION FROM GEMINI..." // Suppressed - show in final summary
                                    try {
                                        def aiSolution = getAISolution(errorReason, additionalContext)
                                        echo """

╔════════════════════════════════════════════════════════════════════════════════╗
║                            BUILD FAILURE ANALYSIS                              ║
╚════════════════════════════════════════════════════════════════════════════════╝

🚨 REASON: ${errorReason}

📋 FAILED STAGE: ${env.BUILD_STAGE}

┌────────────────────────────────────────────────────────────────────────────────┐
│                                  AI FIX                                        │
├────────────────────────────────────────────────────────────────────────────────┤
│ ${aiSolution}                                                                  │
└────────────────────────────────────────────────────────────────────────────────┘
"""
                                    } catch (Exception aiError) {
                                        // AI failed, use fallback fix
                                        echo "🔍 DEBUG: AI Analysis failed: ${aiError.getMessage()}"
                                        
                                        // Fallback to pattern matching
                                        def errorMsg = errorReason.toLowerCase()
                                        // Generate simple fix based on error pattern
                                        if (errorMsg.contains('namespace') && errorMsg.contains('not found')) {
                                            env.AI_FIX = "Namespace '${params.NAMESPACE}' not found. Fix: kubectl create namespace ${params.NAMESPACE}"
                                        } else if (errorMsg.contains('unauthorized') || errorMsg.contains('forbidden')) {
                                            env.AI_FIX = "Permission denied. Fix: Check RBAC policies and service account permissions"
                                        } else if (errorMsg.contains('connection refused') || errorMsg.contains('timeout')) {
                                            env.AI_FIX = "Network connectivity issue. Fix: Check cluster endpoint and firewall settings"
                                        } else if (errorMsg.contains('image') && errorMsg.contains('pull')) {
                                            env.AI_FIX = "Image pull failed. Fix: Check image name, registry access, and credentials"
                                        } else {
                                            env.AI_FIX = "General deployment error. Fix: Check deployment manifests and cluster resources"
                                        }
                                        
                                        echo """

╔════════════════════════════════════════════════════════════════════════════════╗
║                            BUILD FAILURE ANALYSIS                              ║
╚════════════════════════════════════════════════════════════════════════════════╝

🚨 REASON: ${errorReason}

📋 FAILED STAGE: ${env.BUILD_STAGE}

┌────────────────────────────────────────────────────────────────────────────────┐
│                                  AI FIX                                        │
├────────────────────────────────────────────────────────────────────────────────┤
│ ${env.AI_FIX}                                                                  │
└────────────────────────────────────────────────────────────────────────────────┘
"""
                                    }
                                } catch (Exception aiError) {
                                    // AI failed - use pattern matching fallback
                                    def errorMsg = errorReason.toLowerCase()
                                    echo "🔍 DEBUG: Fallback pattern matching for: '${errorReason}'"
                                    echo "🔍 DEBUG: Lowercase version: '${errorMsg}'"
                                    def manualFix = ""
                                    if (errorMsg.contains('namespace') && (errorMsg.contains('not found') || errorMsg.contains('does not exist') || errorMsg.contains("doesn't exist"))) {
                                        manualFix = "ROOT CAUSE: Target namespace '${params.NAMESPACE}' doesn't exist in the cluster\\nFIX: kubectl create namespace ${params.NAMESPACE}\\nPREVENTION: Always verify namespace exists before deployment"
                                        echo "🔍 DEBUG: Matched namespace error pattern"
                                    } else if (errorMsg.contains('unauthorized') || errorMsg.contains('forbidden')) {
                                        manualFix = "ROOT CAUSE: Insufficient permissions to deploy resources\\nFIX: kubectl auth can-i create deployments -n ${params.NAMESPACE}\\nPREVENTION: Ensure service account has proper RBAC permissions"
                                    } else if (errorMsg.contains('connection refused') || errorMsg.contains('timeout')) {
                                        manualFix = "ROOT CAUSE: Cannot connect to Kubernetes API server\\nFIX: kubectl cluster-info (check connectivity)\\nPREVENTION: Verify cluster endpoint and network access"
                                    } else {
                                        echo "🔍 DEBUG: No pattern matched, using generic fix"
                                        manualFix = "ROOT CAUSE: Deployment script failed with exit code 1\\nFIX: Check deployment manifests and cluster resources\\nPREVENTION: Validate manifests before applying"
                                    }
                                    
                                    echo """

╔════════════════════════════════════════════════════════════════════════════════╗
║                            BUILD FAILURE ANALYSIS                              ║
╚════════════════════════════════════════════════════════════════════════════════╝

🚨 REASON: ${errorReason}

📋 FAILED STAGE: ${env.BUILD_STAGE}

┌────────────────────────────────────────────────────────────────────────────────┐
│                                 MANUAL FIX                                     │
├────────────────────────────────────────────────────────────────────────────────┤
│ ${manualFix}                                                                   │
└────────────────────────────────────────────────────────────────────────────────┘
"""
                                }
                                
                                // Troubleshooting details suppressed - show in final summary
                                
                                // Additional debugging suppressed for cleaner output
                                
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
                                if ! kubectl get namespace ${params.NAMESPACE} >/dev/null 2>&1; then
                                    echo "❌ ERROR: Namespace '${params.NAMESPACE}' not found!"
                                    echo "💡 Available namespaces:"
                                    kubectl get namespaces
                                    echo ""
                                    echo "🚨 DEPLOYMENT FAILED: Target namespace does not exist"
                                    echo "📋 TO FIX: Create the namespace first or use an existing one:"
                                    echo "   kubectl create namespace ${params.NAMESPACE}"
                                    echo "   OR use an existing namespace like 'default' or 'test-app'"
                                    # Write specific error for catch block to read
                                    echo "NAMESPACE_NOT_FOUND:${params.NAMESPACE}" > /tmp/deployment_error
                                    exit 1
                                fi
                                echo "✅ Namespace '${params.NAMESPACE}' exists, proceeding with deployment"
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
                                
                                // Store failure info for final summary (verbose analysis suppressed)
                                env.BUILD_FAILED = 'true'
                                
                                // Check for specific error types
                                def specificError = ""
                                def errorReason = e.getMessage()
                                try {
                                    specificError = sh(script: "cat /tmp/deployment_error 2>/dev/null || echo 'GENERIC_ERROR'", returnStdout: true).trim()
                                    if (specificError.startsWith("NAMESPACE_NOT_FOUND:")) {
                                        def missingNamespace = specificError.split(":")[1]
                                        errorReason = "Namespace '${missingNamespace}' does not exist"
                                    }
                                } catch (Exception ex) {
                                    // File doesn't exist, use generic error
                                }
                                env.BUILD_ERROR = errorReason
                                
                                // Clean up error file
                                try {
                                    sh "rm -f /tmp/deployment_error"
                                } catch (Exception ex) {
                                    // Ignore cleanup errors
                                }

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

                                // Get AI-powered solution from Gemini (always enabled)
                                try {
                                    // echo "\n🤖 GETTING AI-POWERED SOLUTION FROM GEMINI..." // Suppressed - show in final summary
                                    try {
                                        def aiSolution = getAISolution(errorReason, additionalContext)
                                        echo """

╔════════════════════════════════════════════════════════════════════════════════╗
║                            BUILD FAILURE ANALYSIS                              ║
╚════════════════════════════════════════════════════════════════════════════════╝

🚨 REASON: ${errorReason}

📋 FAILED STAGE: ${env.BUILD_STAGE}

┌────────────────────────────────────────────────────────────────────────────────┐
│                                  AI FIX                                        │
├────────────────────────────────────────────────────────────────────────────────┤
│ ${aiSolution}                                                                  │
└────────────────────────────────────────────────────────────────────────────────┘
"""
                                    } catch (Exception aiError) {
                                        // AI failed, use fallback fix
                                        echo "🔍 DEBUG: AI Analysis failed: ${aiError.getMessage()}"
                                        
                                        // Fallback to pattern matching
                                        def errorMsg = errorReason.toLowerCase()
                                        // Generate simple fix based on error pattern
                                        if (errorMsg.contains('namespace') && errorMsg.contains('not found')) {
                                            env.AI_FIX = "Namespace '${params.NAMESPACE}' not found. Fix: kubectl create namespace ${params.NAMESPACE}"
                                        } else if (errorMsg.contains('unauthorized') || errorMsg.contains('forbidden')) {
                                            env.AI_FIX = "Permission denied. Fix: Check RBAC policies and service account permissions"
                                        } else if (errorMsg.contains('connection refused') || errorMsg.contains('timeout')) {
                                            env.AI_FIX = "Network connectivity issue. Fix: Check cluster endpoint and firewall settings"
                                        } else if (errorMsg.contains('image') && errorMsg.contains('pull')) {
                                            env.AI_FIX = "Image pull failed. Fix: Check image name, registry access, and credentials"
                                        } else {
                                            env.AI_FIX = "General deployment error. Fix: Check deployment manifests and cluster resources"
                                        }
                                        
                                        echo """

╔════════════════════════════════════════════════════════════════════════════════╗
║                            BUILD FAILURE ANALYSIS                              ║
╚════════════════════════════════════════════════════════════════════════════════╝

🚨 REASON: ${errorReason}

📋 FAILED STAGE: ${env.BUILD_STAGE}

┌────────────────────────────────────────────────────────────────────────────────┐
│                                  AI FIX                                        │
├────────────────────────────────────────────────────────────────────────────────┤
│ ${env.AI_FIX}                                                                  │
└────────────────────────────────────────────────────────────────────────────────┘
"""
                                    }
                                } catch (Exception aiError) {
                                    // AI failed - use pattern matching fallback
                                    def errorMsg = errorReason.toLowerCase()
                                    echo "🔍 DEBUG: Fallback pattern matching for: '${errorReason}'"
                                    echo "🔍 DEBUG: Lowercase version: '${errorMsg}'"
                                    def manualFix = ""
                                    if (errorMsg.contains('namespace') && (errorMsg.contains('not found') || errorMsg.contains('does not exist') || errorMsg.contains("doesn't exist"))) {
                                        manualFix = "ROOT CAUSE: Target namespace '${params.NAMESPACE}' doesn't exist in the cluster\\nFIX: kubectl create namespace ${params.NAMESPACE}\\nPREVENTION: Always verify namespace exists before deployment"
                                        echo "🔍 DEBUG: Matched namespace error pattern"
                                    } else if (errorMsg.contains('unauthorized') || errorMsg.contains('forbidden')) {
                                        manualFix = "ROOT CAUSE: Insufficient permissions to deploy resources\\nFIX: kubectl auth can-i create deployments -n ${params.NAMESPACE}\\nPREVENTION: Ensure service account has proper RBAC permissions"
                                    } else if (errorMsg.contains('connection refused') || errorMsg.contains('timeout')) {
                                        manualFix = "ROOT CAUSE: Cannot connect to Kubernetes API server\\nFIX: kubectl cluster-info (check connectivity)\\nPREVENTION: Verify cluster endpoint and network access"
                                    } else {
                                        echo "🔍 DEBUG: No pattern matched, using generic fix"
                                        manualFix = "ROOT CAUSE: Deployment script failed with exit code 1\\nFIX: Check deployment manifests and cluster resources\\nPREVENTION: Validate manifests before applying"
                                    }
                                    
                                    echo """

╔════════════════════════════════════════════════════════════════════════════════╗
║                            BUILD FAILURE ANALYSIS                              ║
╚════════════════════════════════════════════════════════════════════════════════╝

🚨 REASON: ${errorReason}

📋 FAILED STAGE: ${env.BUILD_STAGE}

┌────────────────────────────────────────────────────────────────────────────────┐
│                                 MANUAL FIX                                     │
├────────────────────────────────────────────────────────────────────────────────┤
│ ${manualFix}                                                                   │
└────────────────────────────────────────────────────────────────────────────────┘
"""
                                }
                                
                                // Troubleshooting details suppressed - show in final summary
                                
                                // Additional debugging suppressed for cleaner output
                                
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
} // end of node block
} // end of node block
} // end of podTemplate block

}

