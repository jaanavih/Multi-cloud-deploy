# Jenkins Cost Comparison Quick Reference

## Files Created
- `vars/costComparison.groovy` - Shared library function
- `scripts/ai_cost_comparison.py` - Deterministic baseline + LLM narrative (OpenAI/Anthropic)
- `Jenkinsfile` - **Default (Option A):** cost + AI → `input()` → deploy
- `Jenkinsfile.cost-gate` - Symlink to `Jenkinsfile` (stable path for older jobs)
- `Jenkinsfile.parameters-first` - Legacy: `CLOUD_PROVIDER` on first parameter screen
- `Jenkinsfile.cost-only` - Analysis-only job (then run deploy separately)
- `cost-comparison-pipeline.jenkinsfile` - Standalone pipeline
- `setup-jenkins-library.sh` - Setup helper script

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
