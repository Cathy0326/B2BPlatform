output "kubeconfig" {
  description = "Base64-encoded kubeconfig: tofu output -raw kubeconfig | base64 -d > ~/.kube/quipmarket.yaml"
  value       = linode_lke_cluster.main.kubeconfig
  sensitive   = true
}

output "db_host" {
  value = linode_database_postgresql_v2.main.host_primary
}

output "db_port" {
  value = linode_database_postgresql_v2.main.port
}

output "db_username" {
  value = linode_database_postgresql_v2.main.root_username
}

output "db_password" {
  value     = linode_database_postgresql_v2.main.root_password
  sensitive = true
}
