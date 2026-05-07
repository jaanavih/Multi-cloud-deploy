# 💰 Multi-Cloud Cost Comparison Feature

This project includes cost comparison capabilities that help you make informed decisions about where to deploy your applications across AWS EKS and GCP GKE.

## 🌟 Features

- **Pre-deployment cost analysis** - See costs before you deploy
- **Interactive HTML reports** - Beautiful cost breakdowns and visualizations  
- **Multiple deployment modes** - Cost-first workflows or traditional parameter-based

## 🚀 Quick Start

### Default Pipeline (Recommended)
Use the root **`Jenkinsfile`** (Option A):

1. **Build with Parameters** → set `SHOW_COST_COMPARISON=true`
2. Build starts → cost reports published to build page
3. **Review HTML report** → click **Continue** → choose **aws** or **gcp**
4. Deploy continues to selected cloud

### Legacy Pipeline  
Use **`Jenkinsfile.parameters-first`** to pick cloud on first parameter screen.

### Cost-Only Analysis
Use **`Jenkinsfile.cost-only`** for analysis without deployment.

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

## 📈 Sample Cost Comparison

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                           💰 COST COMPARISON RESULTS                         ║
╠══════════════════════════════════════════════════════════════════════════════╣
║  AWS EKS (ap-southeast-1):     $95.50/month                                 ║
║  GCP GKE (asia-southeast1):    $78.30/month                                 ║
║                                                                              ║
║  💡 GCP is cheaper by $17.20/month (18% savings)                           ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

## 🔧 Setup Instructions

### 1. Jenkins Configuration (minimal)

1. Pipeline job: **Pipeline script from SCM** → your Git repo and branch
2. **Script Path:** `Jenkinsfile` (or `Jenkinsfile.parameters-first` / `Jenkinsfile.cost-only` as needed)
3. **No Global Pipeline Library** required (cost logic loads from `lib/costComparison.groovy`)

### 2. Required Plugins

Install these Jenkins plugins:
- Pipeline: Groovy
- HTML Publisher  
- Kubernetes (existing)
- AWS Credentials (existing)

### 3. Test the Setup
1. Create a new pipeline job
2. Set **Script Path** to `Jenkinsfile`
3. Run **Build with Parameters** with `SHOW_COST_COMPARISON=true`

## 📁 File Structure

```
├── lib/
│   └── costComparison.groovy          # Cost calculation logic (loaded after checkout)
├── Jenkinsfile                         # Default: cost → input → deploy
├── Jenkinsfile.cost-gate               # Symlink → Jenkinsfile  
├── Jenkinsfile.parameters-first        # Legacy: CLOUD_PROVIDER on first screen
├── Jenkinsfile.cost-only               # Cost-only job
├── cost-comparison-pipeline.jenkinsfile
├── setup-jenkins-library.sh
└── COST_COMPARISON_README.md
```

## 🎨 HTML Report Features

The generated HTML reports include:
- **Side-by-side cost comparison**
- **Interactive cost breakdowns**
- **Visual recommendations**
- **Mobile-responsive design**
- **Savings calculations**

## ⚙️ Customization

### Update Pricing
Modify `lib/costComparison.groovy` to update:
- Instance pricing (check cloud provider websites)
- Regional pricing differences  
- New instance types
- Additional cost factors

### Add New Cloud Providers
Extend the library to support:
- Microsoft Azure
- Alibaba Cloud
- IBM Cloud
- Digital Ocean

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

Jenkins **cannot** render HTML *before* the first "Build with Parameters" screen. Practical patterns:

### A — One job: analyze → pause → choose cloud (`input`)

- **Default pipeline:** root **`Jenkinsfile`** (Option A). CI jobs should use **Script Path** `Jenkinsfile`.
- **`Jenkinsfile.cost-gate`** is a **symlink** to `Jenkinsfile` so existing jobs that pointed at `Jenkinsfile.cost-gate` keep working.
- Flow: Checkout → cost HTML → **`input()`** with AWS/GCP → install tools → kubeconfig → deploy/delete **in the same build**.
- Optional **`DOWNSTREAM_DEPLOY_JOB`**: if set, triggers your existing deploy job with `CLOUD_PROVIDER`, `NAMESPACE`, `ACTION`, and skips inline kubectl stages.

**Legacy (pick cloud on the first parameter screen):** use **`Jenkinsfile.parameters-first`**.

### B — Two jobs: cost-only, then deploy

1. Job **`cost-analysis`** uses **`Jenkinsfile.cost-only`** (artifacts + HTML only).
2. After review, run **`multi-cloud-deploy`** (your **`Jenkinsfile`**) with parameters — including **`CLOUD_PROVIDER`**.

### Where installs run

- With **`podTemplate`**, **`apt-get`**, **`kubectl`**, and **AWS CLI** run inside the **ephemeral Kubernetes agent Pod** (here the **`tools` container**), **not** on the Jenkins controller Pod.
- The controller only orchestrates unless you run builds on the controller (`master` label).

## 🆘 Troubleshooting

### Common Issues

**Could not find any definition of libraries [cost-comparison-library]**
- Update the repo to the latest `main`: pipelines now use `load 'lib/costComparison.groovy'` and **do not** use `@Library('cost-comparison-library')` on the default `Jenkinsfile`.
- If you still see this error, your Jenkins job may be pinned to an old branch/commit, or a forked Jenkinsfile still has `@Library`.

**Cost Comparison Fails**
- Verify repository access and credentials
- Ensure required plugins are installed

**Inaccurate Cost Estimates**
- Update pricing in `lib/costComparison.groovy`
- Check if new instance types are available
- Verify regional pricing differences

**HTML Report Not Showing**
- Ensure HTML Publisher plugin is installed
- Check Jenkins security settings allow HTML content
- Verify report file is being generated

**Auto-Selection Not Working**
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
- **v1.4**: AI narrative layer (removed in v1.7)
- **v1.5**: Option A default with input() gate
- **v1.6**: Remove Global Pipeline Library requirement
- **v1.7**: Remove AI components, simplify to core cost comparison

---

🎉 **Happy cost-optimized deploying!** 💰