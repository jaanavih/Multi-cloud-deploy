#!/bin/bash

# Setup script for Jenkins Shared Library
# This script helps configure the cost comparison shared library in Jenkins

set -e

echo "🔧 Setting up Jenkins Shared Library for Cost Comparison..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║              Jenkins Shared Library Setup Guide               ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"

echo ""
echo -e "${YELLOW}📋 Manual Setup Steps Required in Jenkins:${NC}"
echo ""

echo -e "${GREEN}1. Configure Shared Library:${NC}"
echo "   • Go to Jenkins → Manage Jenkins → Configure System"
echo "   • Scroll to 'Global Pipeline Libraries'"
echo "   • Click 'Add' and configure:"
echo "     - Name: cost-comparison-library"
echo "     - Default version: main (or your branch name)"
echo "     - Source Code Management: Git"
echo "     - Repository URL: $(git remote get-url origin 2>/dev/null || echo 'YOUR_REPO_URL')"
echo "     - Credentials: (your git credentials if private repo)"

echo ""
echo -e "${GREEN}2. Required Jenkins Plugins:${NC}"
echo "   • Pipeline: Groovy"
echo "   • Pipeline: Stage View"
echo "   • HTML Publisher"
echo "   • Kubernetes (if not already installed)"
echo "   • AWS Credentials (if not already installed)"

echo ""
echo -e "${GREEN}3. Verify Plugin Installation:${NC}"
cat << 'EOF'
   Run this Groovy script in Jenkins → Manage Jenkins → Script Console:

   def plugins = ['workflow-aggregator', 'htmlpublisher', 'kubernetes', 'aws-credentials']
   plugins.each { plugin ->
       def installed = Jenkins.instance.pluginManager.plugins.find { it.shortName == plugin }
       println "${plugin}: ${installed ? 'INSTALLED' : 'MISSING'}"
   }
EOF

echo ""
echo -e "${GREEN}4. Directory Structure Created:${NC}"
echo "   ✅ vars/costComparison.groovy (shared library function)"
echo "   ✅ cost-comparison-pipeline.jenkinsfile (standalone pipeline)"
echo "   ✅ Jenkinsfile (updated with cost comparison stage)"

echo ""
echo -e "${GREEN}5. Test the Setup:${NC}"
echo "   • Create a new Pipeline job in Jenkins"
echo "   • Use 'cost-comparison-pipeline.jenkinsfile' as the pipeline script"
echo "   • Run the job to test cost comparison functionality"

echo ""
echo -e "${YELLOW}📊 Usage Examples:${NC}"
echo ""

echo -e "${BLUE}Option A: Standalone Cost Comparison${NC}"
echo "   Create a new pipeline job with 'cost-comparison-pipeline.jenkinsfile'"
echo "   This gives you a dedicated cost analysis pipeline"

echo ""
echo -e "${BLUE}Option B: Integrated with Existing Pipeline${NC}"
echo "   Your existing Jenkinsfile now includes cost comparison"
echo "   Set SHOW_COST_COMPARISON=true when building"

echo ""
echo -e "${GREEN}🚀 Next Steps:${NC}"
echo "1. Commit and push these files to your repository"
echo "2. Configure the shared library in Jenkins (step 1 above)"
echo "3. Install required plugins (step 2 above)"
echo "4. Test with the standalone pipeline first"
echo "5. Then test the integrated pipeline"

echo ""
echo -e "${YELLOW}💡 Pro Tips:${NC}"
echo "• The cost calculations are estimates based on standard pricing"
echo "• Actual costs may vary based on usage patterns and promotions"
echo "• Consider running cost comparison before major deployments"
echo "• Use the HTML report for detailed cost breakdowns"

echo ""
echo -e "${GREEN}✅ Setup files created successfully!${NC}"

# Create a quick reference file
cat > jenkins-cost-comparison-reference.md << 'EOF'
# Jenkins Cost Comparison Quick Reference

## Files Created
- `vars/costComparison.groovy` - Shared library function
- `cost-comparison-pipeline.jenkinsfile` - Standalone pipeline
- `Jenkinsfile` - Updated with cost comparison stage
- `setup-jenkins-library.sh` - This setup script

## Usage

### Standalone Pipeline
```groovy
// Create new pipeline job with cost-comparison-pipeline.jenkinsfile
// Parameters: WORKLOAD_SIZE, EXPECTED_REPLICAS, AWS_REGION, GCP_REGION
```

### Integrated Pipeline
```groovy
// Your existing pipeline now has cost comparison
// Set SHOW_COST_COMPARISON=true in build parameters
```

### Shared Library Function
```groovy
// Use in any pipeline
def costResults = costComparison([
    awsRegion: 'ap-southeast-1',
    gcpRegion: 'asia-southeast1',
    hoursPerMonth: 730
])
```

## Cost Factors Included
- Cluster management fees
- Compute instances
- Load balancers
- Storage
- Data transfer
- Networking

## Customization
Modify `vars/costComparison.groovy` to:
- Update pricing (check cloud provider websites)
- Add new resource types
- Include additional cost factors
- Modify instance type mappings
EOF

echo -e "${BLUE}📖 Created reference file: jenkins-cost-comparison-reference.md${NC}"
echo ""