pipeline {
    agent { label 'hetzner-node-2' }
    stages {
        stage('Stop old test container') {
            steps {
                sh 'docker stop 2dbbb4cbf787'
            }
        }
    }
}
