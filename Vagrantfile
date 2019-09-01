Vagrant.configure("2") do |config|
  config.vm.provider "virtualbox"

  config.vm.define :test_vm1 do |test_vm1|
    test_vm1.vm.box = "generic/debian10"
    test_vm1.vm.network "private_network", ip: "10.0.1.42"
  end

  config.vm.provision "shell" do |s|
    ssh_pub_key_nopassword = File.readlines("core/src/test/resources/nopassword.pub").first.strip
    ssh_pub_key_password   = File.readlines("core/src/test/resources/password.pub").first.strip
    s.inline = <<-SHELL
      echo #{ssh_pub_key_nopassword} >> /home/vagrant/.ssh/authorized_keys
      echo #{ssh_pub_key_password}   >> /home/vagrant/.ssh/authorized_keys
    SHELL
  end
end
