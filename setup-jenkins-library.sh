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

echo -e "${GREEN}1. Global Pipeline Library (OPTIONAL):${NC}"
echo "   • Default Jenkinsfile uses load('lib/costComparison.groovy') — no library entry needed."
echo "   • Only if you use @Library: Manage Jenkins → Configure System → Global Pipeline Libraries"
echo "     - Name: cost-comparison-library"
echo "     - Git repo: $(git remote get-url origin 2>/dev/null || echo 'YOUR_REPO_URL')"

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
echo "   ✅ lib/costComparison.groovy (loaded after checkout)"
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
echo "2. (Optional) Configure Global Pipeline Library only if you use @Library"
echo "3. Install required plugins (see script section 2)"
echo "4. Test with the standalone pipeline first"
echo "5. Then test the integrated pipeline (root Jenkinsfile)"

echo ""
echo -e "${YELLOW}💡 Pro Tips:${NC}"
echo "• The cost calculations are estimates based on standard pricing"
echo "• Actual costs may vary based on usage patterns and promotions"
echo "• Consider running cost comparison before major deployments"
echo "• Use the HTML report for detailed cost breakdowns"

echo ""
echo -e "${GREEN}✅ Setup hints complete.${NC}"

echo -e "${BLUE}📖 See jenkins-cost-comparison-reference.md in the repo.${NC}"
echo ""