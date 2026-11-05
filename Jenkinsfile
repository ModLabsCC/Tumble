pipeline {
    agent { label 'hetzner-node-2' }
    stages {
        stage('Identify old test container') {
            steps {
                sh '''docker ps --filter 'ancestor=eclipse-temurin:25-jdk' --filter 'volume=/var/jenkins_home/workspace/_Tumble_codex_tumble-fabric-port' --format '{{.ID}} {{.Image}} {{.Status}} {{.Names}}' '''
            }
        }
    }
}
