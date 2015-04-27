require 'fileutils'

role :rachis do
  task :mono do
    sudo do
      exec! 'sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 3FA7E0328081BFF6A14DA29AA6A19B38D3D831EF ', echo: true
      exec! 'echo "deb http://download.mono-project.com/repo/debian wheezy main" | sudo tee /etc/apt/sources.list.d/mono-xamarin.list', echo: true
      exec! 'sudo apt-get update', echo: true
      exec! 'sudo apt-get install -y mono-complete screen', echo: true
    end
  end

  task :clone do
    sudo do
      cd '/opt/'
      unless dir? :Rachis
        git :clone, 'https://github.com/ml054/Rachis.git', echo: true
      end
      # since ravendb repository is quite big - consider serving it from local network (git update-server-info might me helpful)
      unless dir? :ravendb
        git :clone, 'http://10.0.3.1/ravendb/', echo: true
      end
    end
  end

  task :build do
    sudo do
      cd '/opt/Rachis/'
      git :pull, echo:true
      cd '/opt/ravendb'
      git :pull, echo:true
      cd '/opt/Rachis/'
      exec! 'xbuild', echo: true
    end
  end

  task :start do
    cd '/opt/Rachis/TailFeather/bin/Debug'
    sudo do
      if name == 'n1'
        exec! 'cli TailFeather.exe --port=9090 --DataPath=db --Name=' + name + " --Bootstrap", echo: true
      end
      log 'Starting TailFeather'
      exec! 'nohup screen -dm /bin/bash -c \'cd /opt/Rachis/TailFeather/bin/Debug; cli TailFeather.exe --port=9090 --DataPath=db --Name=' + name + '; echo \$?;\'', echo: true
      sleep 3
      if name == 'n1'
         ['n2', 'n3', 'n4', 'n5'].each do |host|
            log 'About to call fly-with-us for ' + host
            exec! 'wget "http://n1:9090/tailfeather/admin/fly-with-us?url=http://' + host + ':9090&name=' + host + '" -O /dev/null', echo: true
            sleep 3
         end
      end	
    end
  end

  task :cleanup do
    rachis.stop
    cd '/opt/Rachis/TailFeather/bin/Debug'
    sudo do
      exec! 'rm -rvf db/', echo: true
    end
  end

  task :stop do
    sudo do
      killall 'cli' rescue log "no cli"
    end
  end

end
