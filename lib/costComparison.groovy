/**
 * Cost comparison helpers — loaded via load('lib/costComparison.groovy') after checkout.
 * No Global Pipeline Library required.
 */

Map runCostComparison(Map config) {
    def results = [:]

    try {
        echo '🔍 Starting cost comparison analysis...'

        def resourceSpecs = getResourceSpecs(config)
        results.aws = calculateAWSCosts(resourceSpecs, config)
        results.gcp = calculateGCPCosts(resourceSpecs, config)

        def report = generateCostReport(results)
        writeFile file: 'cost-comparison-report.html', text: report
        archiveArtifacts artifacts: 'cost-comparison-report.html', fingerprint: true

        echo '💰 Cost comparison complete. Report saved to cost-comparison-report.html'
        return results
    } catch (Exception e) {
        echo "❌ Error during cost comparison: ${e.message}"
        throw e
    }
}

def getResourceSpecs(Map config) {
    def specs = [:]

    if (fileExists('k8s/deployment.yaml')) {
        def deploymentContent = readFile('k8s/deployment.yaml')
        specs.replicas = extractReplicas(deploymentContent)
        specs.containers = extractContainerSpecs(deploymentContent)
    }

    if (fileExists('k8s/service.yaml')) {
        def serviceContent = readFile('k8s/service.yaml')
        specs.serviceType = extractServiceType(serviceContent)
    }

    specs.replicas = specs.replicas ?: 1
    specs.containers = specs.containers ?: [[cpu: '100m', memory: '128Mi']]
    specs.serviceType = specs.serviceType ?: 'LoadBalancer'
    specs.region = config.region ?: 'us-east-1'
    specs.hoursPerMonth = config.hoursPerMonth ?: 730

    return specs
}

def extractReplicas(String content) {
    def match = content =~ /replicas:\s*(\d+)/
    return match ? Integer.parseInt(match[0][1]) : 1
}

def extractContainerSpecs(String content) {
    def containers = []
    def defaultSpec = [cpu: '100m', memory: '128Mi']
    containers.add(defaultSpec)
    return containers
}

def extractServiceType(String content) {
    def match = content =~ /type:\s*(\w+)/
    return match ? match[0][1] : 'LoadBalancer'
}

def calculateAWSCosts(Map specs, Map config) {
    def costs = [:]
    costs.clusterManagement = 0.10 * specs.hoursPerMonth

    def instanceType = 't3.medium'
    def instancesNeeded = Math.ceil(specs.replicas / 4)

    def instanceCosts = [
        't3.micro'  : 0.0104,
        't3.small'  : 0.0208,
        't3.medium' : 0.0416,
        't3.large'  : 0.0832
    ]

    costs.compute = instanceCosts[instanceType] * instancesNeeded * specs.hoursPerMonth

    if (specs.serviceType == 'LoadBalancer') {
        costs.loadBalancer = 0.0225 * specs.hoursPerMonth
        costs.dataTransfer = 5.0
    } else {
        costs.loadBalancer = 0
        costs.dataTransfer = 0
    }

    costs.storage = 0.10 * 20 * instancesNeeded
    costs.networking = 2.0
    costs.total = costs.clusterManagement + costs.compute + costs.loadBalancer +
        costs.dataTransfer + costs.storage + costs.networking
    costs.currency = 'USD'
    costs.region = config.awsRegion ?: 'ap-southeast-1'

    return costs
}

def calculateGCPCosts(Map specs, Map config) {
    def costs = [:]
    costs.clusterManagement = 0.10 * specs.hoursPerMonth

    def machineType = 'e2-standard-2'
    def instancesNeeded = Math.ceil(specs.replicas / 4)

    def machineCosts = [
        'e2-micro'        : 0.006,
        'e2-small'        : 0.020,
        'e2-medium'       : 0.040,
        'e2-standard-2'   : 0.080,
        'e2-standard-4'   : 0.160
    ]

    costs.compute = machineCosts[machineType] * instancesNeeded * specs.hoursPerMonth

    if (specs.serviceType == 'LoadBalancer') {
        costs.loadBalancer = 0.025 * specs.hoursPerMonth
        costs.dataTransfer = 4.0
    } else {
        costs.loadBalancer = 0
        costs.dataTransfer = 0
    }

    costs.storage = 0.04 * 20 * instancesNeeded
    costs.networking = 1.5
    costs.total = costs.clusterManagement + costs.compute + costs.loadBalancer +
        costs.dataTransfer + costs.storage + costs.networking
    costs.currency = 'USD'
    costs.region = config.gcpRegion ?: 'asia-southeast1'

    return costs
}

def generateCostReport(Map results) {
    def savings = results.aws.total - results.gcp.total
    def maxTot = Math.max(results.aws.total, results.gcp.total)
    def savingsPercent = maxTot > 0 ? Math.abs(savings / maxTot * 100) : 0
    def cheaperProvider = savings > 0 ? 'GCP' : 'AWS'

    def absSav = String.format('%.2f', Math.abs(savings) as double)
    def pct = String.format('%.1f', savingsPercent as double)
    def awsTot = String.format('%.2f', results.aws.total as double)
    def gcpTot = String.format('%.2f', results.gcp.total as double)

    def html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Multi-Cloud Cost Comparison</title>
    <style>
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 0; padding: 20px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #333; min-height: 100vh; }
        .container { max-width: 1200px; margin: 0 auto; background: white; border-radius: 15px;
            box-shadow: 0 20px 40px rgba(0,0,0,0.1); overflow: hidden; }
        .header { background: linear-gradient(135deg, #2c3e50 0%, #34495e 100%); color: white; padding: 30px; text-align: center; }
        .header h1 { margin: 0; font-size: 2.5em; font-weight: 300; }
        .subtitle { margin: 10px 0 0 0; opacity: 0.8; font-size: 1.1em; }
        .summary { padding: 30px; background: #f8f9fa; border-bottom: 1px solid #e9ecef; }
        .summary-card { border-radius: 10px; padding: 20px; text-align: center;
            background: ${savings > 0 ? '#d4edda' : '#f8d7da'};
            border: 1px solid ${savings > 0 ? '#c3e6cb' : '#f5c6cb'}; }
        .summary-card h2 { margin: 0 0 10px 0; color: ${savings > 0 ? '#155724' : '#721c24'}; }
        .comparison-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 30px; padding: 30px; }
        .provider-card { border-radius: 15px; padding: 25px; box-shadow: 0 5px 15px rgba(0,0,0,0.08); }
        .aws-card { background: linear-gradient(135deg, #ff9a56 0%, #ffad56 100%); color: white; }
        .gcp-card { background: linear-gradient(135deg, #4285f4 0%, #34a853 100%); color: white; }
        .provider-header { display: flex; align-items: center; margin-bottom: 20px; }
        .provider-logo { width: 40px; height: 40px; margin-right: 15px; background: rgba(255,255,255,0.2);
            border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: bold; }
        .total-cost { font-size: 2.5em; font-weight: bold; margin: 20px 0; }
        .cost-breakdown { background: rgba(255,255,255,0.1); border-radius: 10px; padding: 15px; margin-top: 20px; }
        .cost-item { display: flex; justify-content: space-between; margin: 8px 0; padding: 5px 0;
            border-bottom: 1px solid rgba(255,255,255,0.2); }
        .cost-item:last-child { border-bottom: none; font-weight: bold; margin-top: 15px; padding-top: 15px;
            border-top: 2px solid rgba(255,255,255,0.3); }
        .timestamp { text-align: center; padding: 20px; color: #6c757d; font-size: 0.9em; }
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
                <p><strong>${cheaperProvider}</strong> is cheaper by <strong>\$${absSav}</strong> per month</p>
                <p>That's a <strong>${pct}%</strong> difference!</p>
            </div>
        </div>
        <div class="comparison-grid">
            <div class="provider-card aws-card">
                <div class="provider-header">
                    <div class="provider-logo">AWS</div>
                    <div><h3>Amazon Web Services</h3><p>EKS in ${results.aws.region}</p></div>
                </div>
                <div class="total-cost">\$${awsTot}/month</div>
                <div class="cost-breakdown">
                    <div class="cost-item"><span>EKS Cluster Management</span><span>\$${String.format('%.2f', results.aws.clusterManagement as double)}</span></div>
                    <div class="cost-item"><span>EC2 Compute Instances</span><span>\$${String.format('%.2f', results.aws.compute as double)}</span></div>
                    <div class="cost-item"><span>Load Balancer</span><span>\$${String.format('%.2f', results.aws.loadBalancer as double)}</span></div>
                    <div class="cost-item"><span>EBS Storage</span><span>\$${String.format('%.2f', results.aws.storage as double)}</span></div>
                    <div class="cost-item"><span>Data Transfer</span><span>\$${String.format('%.2f', results.aws.dataTransfer as double)}</span></div>
                    <div class="cost-item"><span>Networking</span><span>\$${String.format('%.2f', results.aws.networking as double)}</span></div>
                    <div class="cost-item"><span>Total Monthly Cost</span><span>\$${awsTot}</span></div>
                </div>
            </div>
            <div class="provider-card gcp-card">
                <div class="provider-header">
                    <div class="provider-logo">GCP</div>
                    <div><h3>Google Cloud Platform</h3><p>GKE in ${results.gcp.region}</p></div>
                </div>
                <div class="total-cost">\$${gcpTot}/month</div>
                <div class="cost-breakdown">
                    <div class="cost-item"><span>GKE Cluster Management</span><span>\$${String.format('%.2f', results.gcp.clusterManagement as double)}</span></div>
                    <div class="cost-item"><span>Compute Engine Instances</span><span>\$${String.format('%.2f', results.gcp.compute as double)}</span></div>
                    <div class="cost-item"><span>Load Balancer</span><span>\$${String.format('%.2f', results.gcp.loadBalancer as double)}</span></div>
                    <div class="cost-item"><span>Persistent Disk Storage</span><span>\$${String.format('%.2f', results.gcp.storage as double)}</span></div>
                    <div class="cost-item"><span>Data Transfer</span><span>\$${String.format('%.2f', results.gcp.dataTransfer as double)}</span></div>
                    <div class="cost-item"><span>Networking</span><span>\$${String.format('%.2f', results.gcp.networking as double)}</span></div>
                    <div class="cost-item"><span>Total Monthly Cost</span><span>\$${gcpTot}</span></div>
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

return this
