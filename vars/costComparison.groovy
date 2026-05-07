/**
 * Jenkins Shared Library for Multi-Cloud Cost Comparison
 * Calculates and compares deployment costs between AWS EKS and GCP GKE
 */

def call(Map config) {
    def results = [:]
    
    try {
        echo "🔍 Starting cost comparison analysis..."
        
        // Get resource specifications from deployment
        def resourceSpecs = getResourceSpecs(config)
        
        // Calculate AWS costs
        results.aws = calculateAWSCosts(resourceSpecs, config)
        
        // Calculate GCP costs  
        results.gcp = calculateGCPCosts(resourceSpecs, config)
        
        // Generate comparison report
        def report = generateCostReport(results)
        
        // Save report to workspace
        writeFile file: 'cost-comparison-report.html', text: report
        
        // Archive the report
        archiveArtifacts artifacts: 'cost-comparison-report.html', fingerprint: true
        
        echo "💰 Cost comparison complete. Report saved to cost-comparison-report.html"
        
        return results
        
    } catch (Exception e) {
        echo "❌ Error during cost comparison: ${e.message}"
        throw e
    }
}

def getResourceSpecs(config) {
    def specs = [:]
    
    // Parse Kubernetes deployment files to extract resource requirements
    if (fileExists('k8s/deployment.yaml')) {
        def deploymentContent = readFile('k8s/deployment.yaml')
        specs.replicas = extractReplicas(deploymentContent)
        specs.containers = extractContainerSpecs(deploymentContent)
    }
    
    if (fileExists('k8s/service.yaml')) {
        def serviceContent = readFile('k8s/service.yaml')
        specs.serviceType = extractServiceType(serviceContent)
    }
    
    // Set defaults if not found
    specs.replicas = specs.replicas ?: 1
    specs.containers = specs.containers ?: [[cpu: '100m', memory: '128Mi']]
    specs.serviceType = specs.serviceType ?: 'LoadBalancer'
    
    // Additional config
    specs.region = config.region ?: 'us-east-1'
    specs.hoursPerMonth = config.hoursPerMonth ?: 730 // ~30 days
    
    return specs
}

def extractReplicas(content) {
    def match = content =~ /replicas:\s*(\d+)/
    return match ? Integer.parseInt(match[0][1]) : 1
}

def extractContainerSpecs(content) {
    // Simple extraction - in production, you'd use a YAML parser
    def containers = []
    def defaultSpec = [
        cpu: '100m',      // 0.1 CPU cores
        memory: '128Mi'   // 128 MB
    ]
    containers.add(defaultSpec)
    return containers
}

def extractServiceType(content) {
    def match = content =~ /type:\s*(\w+)/
    return match ? match[0][1] : 'LoadBalancer'
}

def calculateAWSCosts(specs, config) {
    def costs = [:]
    
    // AWS EKS Cluster Cost
    costs.clusterManagement = 0.10 * specs.hoursPerMonth // $0.10/hour
    
    // EC2 instances for worker nodes (estimate based on workload)
    def instanceType = 't3.medium' // Default for small workloads
    def instancesNeeded = Math.ceil(specs.replicas / 4) // Estimate 4 pods per instance
    
    def instanceCosts = [
        't3.micro': 0.0104,
        't3.small': 0.0208,
        't3.medium': 0.0416,
        't3.large': 0.0832
    ]
    
    costs.compute = instanceCosts[instanceType] * instancesNeeded * specs.hoursPerMonth
    
    // Load Balancer costs
    if (specs.serviceType == 'LoadBalancer') {
        costs.loadBalancer = 0.0225 * specs.hoursPerMonth // Application Load Balancer
        costs.dataTransfer = 5.0 // Estimated monthly data transfer
    } else {
        costs.loadBalancer = 0
        costs.dataTransfer = 0
    }
    
    // Storage (EBS)
    costs.storage = 0.10 * 20 * instancesNeeded // $0.10/GB/month for 20GB per instance
    
    // Networking
    costs.networking = 2.0 // Estimated monthly networking costs
    
    costs.total = costs.clusterManagement + costs.compute + costs.loadBalancer + 
                  costs.dataTransfer + costs.storage + costs.networking
    
    costs.currency = 'USD'
    costs.region = config.awsRegion ?: 'ap-southeast-1'
    
    return costs
}

def calculateGCPCosts(specs, config) {
    def costs = [:]
    
    // GKE Cluster Management (free for zonal, $0.10/hour for regional)
    costs.clusterManagement = 0.10 * specs.hoursPerMonth // Assuming regional cluster
    
    // Compute Engine instances
    def machineType = 'e2-standard-2' // Default for small workloads
    def instancesNeeded = Math.ceil(specs.replicas / 4)
    
    def machineCosts = [
        'e2-micro': 0.006,
        'e2-small': 0.020,
        'e2-medium': 0.040,
        'e2-standard-2': 0.080,
        'e2-standard-4': 0.160
    ]
    
    costs.compute = machineCosts[machineType] * instancesNeeded * specs.hoursPerMonth
    
    // Load Balancer costs
    if (specs.serviceType == 'LoadBalancer') {
        costs.loadBalancer = 0.025 * specs.hoursPerMonth // External Load Balancer
        costs.dataTransfer = 4.0 // Estimated monthly data transfer (cheaper than AWS)
    } else {
        costs.loadBalancer = 0
        costs.dataTransfer = 0
    }
    
    // Persistent Disk storage
    costs.storage = 0.04 * 20 * instancesNeeded // $0.04/GB/month for standard persistent disk
    
    // Networking
    costs.networking = 1.5 // Estimated monthly networking costs
    
    costs.total = costs.clusterManagement + costs.compute + costs.loadBalancer + 
                  costs.dataTransfer + costs.storage + costs.networking
    
    costs.currency = 'USD'
    costs.region = config.gcpRegion ?: 'asia-southeast1'
    
    return costs
}

def generateCostReport(results) {
    def savings = results.aws.total - results.gcp.total
    def savingsPercent = Math.abs(savings / Math.max(results.aws.total, results.gcp.total) * 100)
    def cheaperProvider = savings > 0 ? 'GCP' : 'AWS'
    
    def html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Multi-Cloud Cost Comparison</title>
    <style>
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            margin: 0;
            padding: 20px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: #333;
            min-height: 100vh;
        }
        .container {
            max-width: 1200px;
            margin: 0 auto;
            background: white;
            border-radius: 15px;
            box-shadow: 0 20px 40px rgba(0,0,0,0.1);
            overflow: hidden;
        }
        .header {
            background: linear-gradient(135deg, #2c3e50 0%, #34495e 100%);
            color: white;
            padding: 30px;
            text-align: center;
        }
        .header h1 {
            margin: 0;
            font-size: 2.5em;
            font-weight: 300;
        }
        .subtitle {
            margin: 10px 0 0 0;
            opacity: 0.8;
            font-size: 1.1em;
        }
        .summary {
            padding: 30px;
            background: #f8f9fa;
            border-bottom: 1px solid #e9ecef;
        }
        .summary-card {
            background: ${savings > 0 ? '#d4edda' : '#f8d7da'};
            border: 1px solid ${savings > 0 ? '#c3e6cb' : '#f5c6cb'};
            border-radius: 10px;
            padding: 20px;
            text-align: center;
        }
        .summary-card h2 {
            margin: 0 0 10px 0;
            color: ${savings > 0 ? '#155724' : '#721c24'};
        }
        .comparison-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 30px;
            padding: 30px;
        }
        .provider-card {
            border-radius: 15px;
            padding: 25px;
            box-shadow: 0 5px 15px rgba(0,0,0,0.08);
        }
        .aws-card {
            background: linear-gradient(135deg, #ff9a56 0%, #ffad56 100%);
            color: white;
        }
        .gcp-card {
            background: linear-gradient(135deg, #4285f4 0%, #34a853 100%);
            color: white;
        }
        .provider-header {
            display: flex;
            align-items: center;
            margin-bottom: 20px;
        }
        .provider-logo {
            width: 40px;
            height: 40px;
            margin-right: 15px;
            background: rgba(255,255,255,0.2);
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: bold;
        }
        .total-cost {
            font-size: 2.5em;
            font-weight: bold;
            margin: 20px 0;
        }
        .cost-breakdown {
            background: rgba(255,255,255,0.1);
            border-radius: 10px;
            padding: 15px;
            margin-top: 20px;
        }
        .cost-item {
            display: flex;
            justify-content: space-between;
            margin: 8px 0;
            padding: 5px 0;
            border-bottom: 1px solid rgba(255,255,255,0.2);
        }
        .cost-item:last-child {
            border-bottom: none;
            font-weight: bold;
            margin-top: 15px;
            padding-top: 15px;
            border-top: 2px solid rgba(255,255,255,0.3);
        }
        .timestamp {
            text-align: center;
            padding: 20px;
            color: #6c757d;
            font-size: 0.9em;
        }
        @media (max-width: 768px) {
            .comparison-grid {
                grid-template-columns: 1fr;
                gap: 20px;
                padding: 20px;
            }
            .header h1 {
                font-size: 2em;
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🌥️ Multi-Cloud Cost Comparison</h1>
            <p class="subtitle">AWS EKS vs Google Cloud GKE</p>
        </div>
        
        <div class="summary">
            <div class="summary-card">
                <h2>💰 Cost Analysis Result</h2>
                <p><strong>${cheaperProvider}</strong> is cheaper by <strong>\$${Math.abs(savings).toFixed(2)}</strong> per month</p>
                <p>That's a <strong>${savingsPercent.toFixed(1)}%</strong> difference!</p>
            </div>
        </div>
        
        <div class="comparison-grid">
            <div class="provider-card aws-card">
                <div class="provider-header">
                    <div class="provider-logo">AWS</div>
                    <div>
                        <h3>Amazon Web Services</h3>
                        <p>EKS in ${results.aws.region}</p>
                    </div>
                </div>
                <div class="total-cost">\$${results.aws.total.toFixed(2)}/month</div>
                <div class="cost-breakdown">
                    <div class="cost-item">
                        <span>EKS Cluster Management</span>
                        <span>\$${results.aws.clusterManagement.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>EC2 Compute Instances</span>
                        <span>\$${results.aws.compute.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Load Balancer</span>
                        <span>\$${results.aws.loadBalancer.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>EBS Storage</span>
                        <span>\$${results.aws.storage.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Data Transfer</span>
                        <span>\$${results.aws.dataTransfer.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Networking</span>
                        <span>\$${results.aws.networking.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Total Monthly Cost</span>
                        <span>\$${results.aws.total.toFixed(2)}</span>
                    </div>
                </div>
            </div>
            
            <div class="provider-card gcp-card">
                <div class="provider-header">
                    <div class="provider-logo">GCP</div>
                    <div>
                        <h3>Google Cloud Platform</h3>
                        <p>GKE in ${results.gcp.region}</p>
                    </div>
                </div>
                <div class="total-cost">\$${results.gcp.total.toFixed(2)}/month</div>
                <div class="cost-breakdown">
                    <div class="cost-item">
                        <span>GKE Cluster Management</span>
                        <span>\$${results.gcp.clusterManagement.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Compute Engine Instances</span>
                        <span>\$${results.gcp.compute.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Load Balancer</span>
                        <span>\$${results.gcp.loadBalancer.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Persistent Disk Storage</span>
                        <span>\$${results.gcp.storage.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Data Transfer</span>
                        <span>\$${results.gcp.dataTransfer.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Networking</span>
                        <span>\$${results.gcp.networking.toFixed(2)}</span>
                    </div>
                    <div class="cost-item">
                        <span>Total Monthly Cost</span>
                        <span>\$${results.gcp.total.toFixed(2)}</span>
                    </div>
                </div>
            </div>
        </div>
        
        <div class="timestamp">
            Generated on ${new Date().toString()} | 
            Estimates based on current pricing and standard configurations
        </div>
    </div>
</body>
</html>
"""
    
    return html
}