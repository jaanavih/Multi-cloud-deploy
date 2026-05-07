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
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: 'aws-creds'
                    ]]) {
                        sh '''
                        mkdir -p /root/.kube
                        aws eks update-kubeconfig --region ap-southeast-1 --name hello-cluster
                        kubectl get nodes
                        '''
                    }
                } else if (env.TARGET_CLOUD == 'gcp') {
                    withCredentials([
                        file(credentialsId: 'gcp-sa-key', variable: 'GCP_KEY')
                    ]) {
                        sh '''
                        export GOOGLE_APPLICATION_CREDENTIALS=$GCP_KEY
                        gcloud auth activate-service-account --key-file=$GOOGLE_APPLICATION_CREDENTIALS
                        gcloud config set project gke-qa2-36938
                        gcloud container clusters get-credentials gke-qa2-sg1 \
                          --zone asia-southeast1 --project gke-qa2-36938 --internal-ip
                        kubectl get nodes
                        '''
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
                            sh """
                            echo "🚀 Applying Kubernetes manifests..."
                            kubectl apply -n ${params.NAMESPACE} -f k8s/deployment.yaml
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
                            else
                                echo "✅ All pods are ready and running!"
                            fi
                            """
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
                            sh """
                            kubectl apply -n ${params.NAMESPACE} -f k8s/deployment.yaml
                            kubectl apply -n ${params.NAMESPACE} -f k8s/service.yaml
                            kubectl get pods,svc -n ${params.NAMESPACE}
                            """
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
}
