import { LockOutlined, UserOutlined } from "@ant-design/icons";
import { Button, Card, Form, Input, Typography, message } from "antd";
import { useState } from "react";
import { login } from "../utils";

const { Paragraph, Text, Title } = Typography;

const LoginForm = ({ onSuccess }) => {
  const [loading, setLoading] = useState(false);

  const onFinish = (data) => {
    setLoading(true);

    login(data)
      .then(() => {
        message.success("Login successful");
        onSuccess();
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setLoading(false);
      });
  };

  return (
    <Card className="auth-card login-card">
      <Text className="auth-card__eyebrow">Demo access</Text>
      <Title level={3} className="auth-card__title">
        Sign in to enter the storefront.
      </Title>
      <Paragraph className="auth-card__text">
        Use the seeded account below to test the browsing, cart, and checkout
        interactions end to end.
      </Paragraph>

      <div className="auth-card__demo">
        <Text code>demo@laifood.com</Text>
        <Text code>demo123</Text>
      </div>

      <Form name="login" onFinish={onFinish} layout="vertical">
        <Form.Item
          label="Email"
          name="username"
          rules={[{ required: true, message: "Please input your email" }]}
        >
          <Input
            className="auth-input"
            prefix={<UserOutlined />}
            placeholder="demo@laifood.com"
          />
        </Form.Item>

        <Form.Item
          label="Password"
          name="password"
          rules={[{ required: true, message: "Please input your password" }]}
        >
          <Input.Password
            className="auth-input"
            prefix={<LockOutlined />}
            placeholder="demo123"
          />
        </Form.Item>

        <Form.Item style={{ marginBottom: 0 }}>
          <Button
            className="auth-submit"
            type="primary"
            htmlType="submit"
            loading={loading}
            block
          >
            Enter Luc1fer&apos;s Food Bank
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
};

export default LoginForm;
