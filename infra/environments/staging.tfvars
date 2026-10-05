# Staging: the same shape as production (node count, Kubernetes version), on smaller plans to save cost.
#   tofu workspace select -or-create staging && tofu apply -var-file=environments/staging.tfvars
environment        = "staging"
cloudflare_zone_id = "your-zone-id"
domain             = "staging.quipmarket.example.com"
region             = "us-ord"
node_type          = "g6-standard-2"
node_count_min     = 2
node_count_max     = 3
db_type            = "g6-nanode-1"
db_allow_list      = []
ingress_ip         = ""
