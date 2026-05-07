# 💰 Multi-Cloud Cost Comparison Feature

This project now includes intelligent cost comparison capabilities that help you make informed decisions about where to deploy your applications across AWS and GCP.

## 🌟 Features

- **Pre-deployment cost analysis** - See costs before you deploy
- **Smart provider selection** - Automatically choose the cheapest option
- **Interactive HTML reports** - Beautiful cost breakdowns and visualizations
- **Real-time recommendations** - Get optimization tips based on your workload
- **Multiple deployment modes** - Standalone analysis or integrated pipeline

## 🚀 Quick Start

### Option 1: Enhanced Pipeline (Recommended)
Use the enhanced Jenkinsfile that includes smart cost analysis:

```bash
# Use Jenkinsfile-enhanced for your pipeline
# It includes auto-provider selection and cost optimization
```

**Key Parameters:**
- `CLOUD_PROVIDER`: Choose `auto-select` for cost-based selection
- `SHOW_COST_COMPARISON`: Enable cost analysis (recommended)
- `WORKLOAD_SIZE`: Affects cost calculations (small/medium/large)
- `ACTION`: Choose `cost-analysis-only` for analysis without deployment

### Option 2: Standalone Cost Analysis
Run cost comparison without deploying:

```bash
# Create a pipeline job using cost-comparison-pipeline.jenkinsfile
# Perfect for planning and budgeting
```

### Option 3: Integrated Analysis
Your existing pipeline now supports cost comparison:

```bash
# Set SHOW_COST_COMPARISON=true in build parameters
# Cost analysis runs before deployment selection
```

## 📊 What Gets Analyzed

The cost comparison includes:

### AWS EKS Costs
- EKS cluster management fees ($0.10/hour)
- EC2 compute instances (t3.medium default)
- Application Load Balancer
- EBS storage (20GB per instance)
- Data transfer and networking

### GCP GKE Costs  
- GKE cluster management (regional)
- Compute Engine instances (e2-standard-2 default)
- External Load Balancer
- Persistent disk storage (20GB per instance)
- Data transfer and networking

## 🎯 Smart Features

### Auto Provider Selection
Set `CLOUD_PROVIDER=auto-select` and the pipeline will:
1. Calculate costs for both AWS and GCP
2. Automatically select the cheaper option
3. Deploy to the most cost-effective cloud

### Cost Optimization Mode
Enable `COST_OPTIMIZATION_MODE=true` for:
- Detailed optimization recommendations
- Instance type suggestions
- Savings plan recommendations
- Resource utilization tips

### Workload-Based Calculations
Choose your workload size for accurate estimates:
- **Small**: 1-2 replicas, basic resources
- **Medium**: 3-5 replicas, standard resources  
- **Large**: 6+ replicas, high-performance resources

## 📈 Sample Cost Comparison

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                           💰 COST COMPARISON RESULTS                         ║
╠══════════════════════════════════════════════════════════════════════════════╣
║  AWS EKS (ap-southeast-1):     $95.50/month                                 ║
║  GCP GKE (asia-southeast1):    $78.30/month                                 ║
║                                                                              ║
║  💡 GCP is cheaper by $17.20/month (18% savings)                           ║
║  🎉 Annual savings potential: $206.40                                        ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

## 🔧 Setup Instructions

### 1. Configure Jenkins Shared Library
```bash
# Run the setup script for guidance
./setup-jenkins-library.sh
```

### 2. Jenkins Configuration
1. Go to **Manage Jenkins** → **Configure System**
2. Add **Global Pipeline Libraries**:
   - Name: `cost-comparison-library`
   - Default version: `main`
   - Source: Your repository URL

### 3. Required Plugins
Install these Jenkins plugins:
- Pipeline: Groovy
- HTML Publisher
- Kubernetes (existing)
- AWS Credentials (existing)

### 4. Test the Setup
1. Create a new pipeline job
2. Use `cost-comparison-pipeline.jenkinsfile`
3. Run to verify cost analysis works

## 📁 File Structure

```
├── vars/
│   └── costComparison.groovy          # Shared library function
├── Jenkinsfile                         # Original pipeline (updated)
├── Jenkinsfile-enhanced                # Enhanced pipeline with smart features
├── cost-comparison-pipeline.jenkinsfile # Standalone cost analysis
├── setup-jenkins-library.sh           # Setup helper script
└── COST_COMPARISON_README.md          # This documentation
```

## 🎨 HTML Report Features

The generated HTML reports include:
- **Side-by-side cost comparison**
- **Interactive cost breakdowns**
- **Visual recommendations**
- **Mobile-responsive design**
- **Savings calculations**
- **Optimization tips**

## ⚙️ Customization

### Update Pricing
Modify `vars/costComparison.groovy` to update:
- Instance pricing (check cloud provider websites)
- Regional pricing differences  
- New instance types
- Additional cost factors

### Add New Cloud Providers
Extend the shared library to support:
- Microsoft Azure
- Alibaba Cloud
- IBM Cloud
- Digital Ocean

### Custom Cost Factors
Add your specific costs:
- Monitoring and logging
- Backup and disaster recovery
- Security and compliance tools
- Support plans

## 🔍 Cost Calculation Logic

### Base Calculations
```groovy
// AWS Example
costs.clusterManagement = 0.10 * hoursPerMonth
costs.compute = instanceCost * instanceCount * hoursPerMonth
costs.loadBalancer = 0.0225 * hoursPerMonth
costs.storage = 0.10 * storageGB * instanceCount
```

### Scaling Factors
- **Replicas**: More replicas = more instances needed
- **Workload Size**: Affects instance type selection
- **Region**: Different regions have different pricing
- **Service Type**: LoadBalancer vs ClusterIP affects costs

## 💡 Pro Tips

### Cost Optimization
1. **Use auto-scaling** to match actual demand
2. **Consider spot/preemptible instances** for non-critical workloads
3. **Right-size your resources** based on monitoring data
4. **Use reserved instances** for predictable workloads
5. **Monitor and optimize** regularly

### Best Practices
1. **Run cost analysis** before major deployments
2. **Compare monthly and annual costs** for better planning
3. **Consider non-cost factors** like performance and features
4. **Set up alerts** for unexpected cost increases
5. **Review costs regularly** as your usage patterns change

## Cost-first workflows (reports before picking AWS/GCP)

Jenkins **cannot** render HTML *before* the first “Build with Parameters” screen. Practical patterns:

### A — One job: analyze → pause → choose cloud (`input`)

- **Default pipeline:** root **`Jenkinsfile`** (Option A). CI jobs should use **Script Path** `Jenkinsfile`.
- **`Jenkinsfile.cost-gate`** is a **symlink** to `Jenkinsfile` so existing jobs that pointed at `Jenkinsfile.cost-gate` keep working.
- Flow: Checkout → cost HTML (+ optional AI) → **`input()`** with AWS/GCP → install tools → kubeconfig → deploy/delete **in the same build**.
- Optional **`DOWNSTREAM_DEPLOY_JOB`**: if set, triggers your existing deploy job with `CLOUD_PROVIDER`, `NAMESPACE`, `ACTION`, and skips inline kubectl stages.

**Legacy (pick cloud on the first parameter screen):** use **`Jenkinsfile.parameters-first`**.

### B — Two jobs: cost-only, then deploy

1. Job **`cost-analysis`** uses **`Jenkinsfile.cost-only`** (artifacts + HTML only).
2. After review, run **`multi-cloud-deploy`** (your **`Jenkinsfile`**) with parameters — including **`CLOUD_PROVIDER`**.

### AI usage cost (money)

- **Jenkins**: no extra license; you already pay for agents/cluster.
- **LLM API**: charged **per token** (request + response). For `gpt-4o-mini` with a short JSON reply, expect **well under a typical cent per run** in many setups — **verify live totals** on [OpenAI API pricing](https://openai.com/api/pricing/) (and your org’s spend dashboard).
- **Anthropic**: same idea — see their pricing page if you use Claude.

Infra savings tip: bake **`google/cloud-sdk` + Python deps** into a **custom agent image** so you skip `apt-get`/`pip` every build.

### Where installs run

- With **`podTemplate`**, **`apt-get`**, **`pip`**, **`kubectl`**, and **AWS CLI** run inside the **ephemeral Kubernetes agent Pod** (here the **`tools` container**), **not** necessarily on the Jenkins controller Pod.
- The controller only orchestrates unless you run builds on the controller (`master` label).

## 🤖 AI narrative (OpenAI / Anthropic)

The repo includes `scripts/ai_cost_comparison.py`, which:

- Computes **deterministic** monthly USD estimates from `k8s/` (aligned with `vars/costComparison.groovy`).
- Sends that baseline JSON to an LLM for **narrative only** (drivers, exclusions, optimization ideas). Final totals in HTML always come from the baseline so prices are not hallucinated.

**Jenkins**

1. Add a **Secret text** credential with ID `openai-api-key` (your API key).
2. Enable build parameter **USE_AI_COST_NARRATIVE** (default on).
3. Open build artifacts / **🤖 AI Cost Narrative** HTML report.

If the credential is missing, the script runs in **baseline-only** mode and still writes `cost-comparison-ai-report.html`.

**Local**

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r scripts/requirements.txt
export OPENAI_API_KEY="sk-..."
python3 scripts/ai_cost_comparison.py --out-dir ./out
# Or: export ANTHROPIC_API_KEY and run with --provider anthropic
```

## 🆘 Troubleshooting

### Common Issues

**Cost Comparison Fails**
- Check Jenkins shared library configuration
- Verify repository access and credentials
- Ensure required plugins are installed

**Inaccurate Cost Estimates**
- Update pricing in `costComparison.groovy`
- Check if new instance types are available
- Verify regional pricing differences

**HTML Report Not Showing**
- Ensure HTML Publisher plugin is installed
- Check Jenkins security settings allow HTML content
- Verify report file is being generated

**Auto-Selection Not Working**
- Confirm `CLOUD_PROVIDER=auto-select` parameter
- Check cost calculation is completing successfully
- Review Jenkins logs for error messages

## 📞 Support

For issues or questions:
1. Check the Jenkins build logs
2. Review the HTML cost report for details
3. Verify cloud provider pricing hasn't changed
4. Test with the standalone pipeline first

## 🔄 Updates and Maintenance

### Regular Updates
- **Monthly**: Check cloud provider pricing updates
- **Quarterly**: Review and update instance type mappings
- **Annually**: Validate cost calculation accuracy against actual bills

### Version History
- **v1.0**: Initial cost comparison implementation
- **v1.1**: Smart provider selection
- **v1.2**: Enhanced HTML reports
- **v1.3**: Cost optimization recommendations
- **v1.4**: AI narrative layer (`scripts/ai_cost_comparison.py`) with Jenkins integration
- **v1.5**: Option A is default root **`Jenkinsfile`** (`input` after reports); **`Jenkinsfile.cost-gate`** symlink; **`Jenkinsfile.parameters-first`** legacy; **`Jenkinsfile.cost-only`** two-job flow

---

🎉 **Happy cost-optimized deploying!** 💰